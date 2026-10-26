"""统一 MCP 执行边界：实际尝试埋点、共享预算及写入结果保护。"""

import asyncio
from contextlib import contextmanager
from contextvars import ContextVar
from datetime import datetime, timezone
from hashlib import sha256
import json
import logging
from time import perf_counter
from uuid import uuid4

from app.core.config.common.mcp import get_mcp_settings
from app.core.exception.error_code import ErrorCode
from app.core.exception.exceptions import BusinessException, McpClientError
from app.core.flow_logging import record_tool_call, log_turn_step


logger = logging.getLogger("app.tool")
_current: ContextVar["ToolExecutor | None"] = ContextVar("tool_executor", default=None)
attempt_timeout: ContextVar[float | None] = ContextVar("tool_attempt_timeout", default=None)


class ToolExecutor:
    def __init__(self, *, settings=None, repository_factory=None, user_id=None,
                 session_id=None, turn_id=None):
        self.settings = settings or get_mcp_settings()
        self.repository_factory = repository_factory
        self._repository = None
        self.user_id, self.session_id = user_id, session_id
        self.turn_id = turn_id or uuid4().hex
        self.operation_scope = self.turn_id
        self.mark_write_started = None
        self.count = 0
        self.spent_ms = 0.0
        self.reserved_ms = 0.0
        self.calls = []
        self.logical_ids = set()
        self.agent = "unknown"

    @property
    def repository(self):
        if self._repository is None and self.repository_factory is not None:
            self._repository = self.repository_factory()
        return self._repository

    def reserve(self, requested_ms):
        # 此段没有 await，在同一事件循环内原子预留，并发请求不会超卖预算。
        if self.count >= self.settings.turn_max_calls:
            raise BusinessException(ErrorCode.TOOL_CALL_LIMIT)
        remaining = self.settings.turn_budget_ms - self.spent_ms - self.reserved_ms
        if remaining < 1:
            raise BusinessException(ErrorCode.TOOL_BUDGET_EXHAUSTED)
        allowance = min(requested_ms, int(remaining))
        self.count += 1
        self.reserved_ms += allowance
        return allowance

    def check_batch(self, count):
        if self.count + count > self.settings.turn_max_calls:
            raise BusinessException(ErrorCode.TOOL_CALL_LIMIT)

    async def retry_delay(self, delay_ms):
        remaining = self.settings.turn_budget_ms - self.spent_ms - self.reserved_ms
        if remaining <= delay_ms:
            raise BusinessException(ErrorCode.TOOL_BUDGET_EXHAUSTED)
        if self.count >= self.settings.turn_max_calls:
            raise BusinessException(ErrorCode.TOOL_CALL_LIMIT)
        self.reserved_ms += delay_ms
        started = perf_counter()
        try:
            await asyncio.sleep(delay_ms / 1000)
        finally:
            self.reserved_ms -= delay_ms
            self.spent_ms += (perf_counter() - started) * 1000

    async def execute(self, mcp, name, arguments, token, *, read_only, validator=None,
                      timeout_ms=None, logical_call_id=None, operation_scope=None, retry_policy=None,
                      operation=None, allow_retry=True):
        logical_id = logical_call_id or uuid4().hex
        if len(logical_id) > 64 or logical_id in self.logical_ids:
            logical_id = uuid4().hex
        self.logical_ids.add(logical_id)
        policy = retry_policy or self.settings
        attempts = policy.read_retry_attempts if read_only and allow_retry else 1
        requested_ms = timeout_ms if timeout_ms and timeout_ms > 0 else self.settings.call_timeout_ms
        for number in range(1, attempts + 1):
            allowance = self.reserve(requested_ms)
            attempt_id = uuid4().hex
            scope = operation_scope or self.operation_scope
            operation_key = None if read_only else sha256(json.dumps(
                [self.user_id, self.session_id, scope, name, arguments],
                sort_keys=True, ensure_ascii=False, separators=(",", ":"),
            ).encode()).hexdigest()
            row = dict(attempt_id=attempt_id, logical_call_id=logical_id, turn_id=self.turn_id,
                       user_id=self.user_id, session_id=self.session_id,
                       agent=self.agent,
                       tool_name=name, read_only=read_only, attempt_no=number,
                       operation_key=operation_key, status="running",
                       outcome="pending" if read_only else "uncertain", timeout_ms=allowance,
                       started_at=datetime.now(timezone.utc).replace(tzinfo=None))
            # 落库失败不得执行外部操作，也不伪造一次真实尝试。
            try:
                if self.repository is not None:
                    async with asyncio.timeout(5):
                        await self.repository.start(row)
            except BaseException:
                self.reserved_ms -= allowance
                self.count -= 1
                raise

            status, outcome, error_code = "error", "failed", None
            started = perf_counter()
            sent = False
            timeout_token = attempt_timeout.set(allowance / 1000)
            logger.info("工具调用开始 | tool=%s attempt_id=%s turn_id=%s attempt=%s timeout_ms=%s",
                        name, attempt_id, self.turn_id, number, allowance,
                        extra={"tool_event": "start", **row, "started_at": row["started_at"].isoformat() + "Z"})
            try:
                if not read_only and self.mark_write_started is not None:
                    self.mark_write_started()
                sent = True
                async with asyncio.timeout(allowance / 1000):
                    result = await operation() if operation is not None else await mcp.call_tool(name, arguments, token)
                    result = validator(result) if validator is not None else result
                status, outcome = "success", "success"
                return result
            except BaseException as exc:
                cancelled = isinstance(exc, asyncio.CancelledError)
                timed_out = isinstance(exc, TimeoutError) or (
                    isinstance(exc, McpClientError) and exc.code == "JAVA_TIMEOUT")
                status = "cancelled" if cancelled else "timeout" if timed_out else "error"
                error_code = str(getattr(exc, "code", type(exc).__name__))[:64]
                # 写请求发出后，无法从传输或校验异常证明未写入，保守待核实。
                outcome = "uncertain" if not read_only and sent else "failed"
                if not read_only or cancelled:
                    raise
                if isinstance(exc, TimeoutError):
                    exc = McpClientError("JAVA_TIMEOUT", "工具调用超时", retryable=True)
                if not isinstance(exc, McpClientError) or not exc.retryable or number == attempts:
                    raise exc
                retry_error = exc
            finally:
                attempt_timeout.reset(timeout_token)
                elapsed = (perf_counter() - started) * 1000
                self.reserved_ms -= allowance
                self.spent_ms += elapsed
                terminal = dict(status=status, outcome=outcome, elapsed_ms=round(elapsed, 3),
                                error_code=error_code)
                event = {**row, **terminal, "started_at": row["started_at"].isoformat() + "Z"}
                self.calls.append(event)
                record_tool_call(event, turn_id=self.turn_id)
                logger.log(logging.INFO if status == "success" else logging.WARNING,
                           "工具调用结束 | tool=%s attempt_id=%s turn_id=%s status=%s outcome=%s elapsed_ms=%.3f code=%s",
                           name, attempt_id, self.turn_id, status, outcome, elapsed, error_code,
                           extra={"tool_event": status, **event})
                if self.repository is not None:
                    # 请求被取消后仍给终态落库一个有界机会；失败保留 running/uncertain。
                    task = asyncio.create_task(self._finish(attempt_id, terminal))
                    interrupted = None
                    while True:
                        try:
                            await asyncio.shield(task)
                            break
                        except asyncio.CancelledError as exc:
                            interrupted = exc
                            if task.done():
                                task.result()
                                break
                        except Exception:
                            if interrupted is not None:
                                raise interrupted
                            raise
                    if interrupted is not None:
                        raise interrupted
            log_turn_step("READ_TOOL_RETRY", 工具=name, 下一次尝试=number + 1, 错误码=retry_error.code)
            await self.retry_delay(policy.read_retry_delay_ms)
        raise AssertionError("工具重试应已返回或抛出异常")

    async def _finish(self, attempt_id, terminal):
        try:
            async with asyncio.timeout(5):
                await self.repository.finish(attempt_id, terminal)
        except Exception:
            logger.exception("工具终态落库失败 | attempt_id=%s", attempt_id)
            # 已知成功的写入也不能在审计落库失败后继续提交/释放会话。
            if terminal["status"] != "cancelled" and terminal["outcome"] in {"success", "uncertain"}:
                raise BusinessException(ErrorCode.TOOL_RECORD_FAILED)


def get_tool_executor(*, settings=None):
    return _current.get() or ToolExecutor(settings=settings)


@contextmanager
def bind_tool_executor(executor):
    token = _current.set(executor)
    try:
        yield executor
    finally:
        _current.reset(token)
