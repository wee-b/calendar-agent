"""执行约束和持久化失败边界；不连接模型、Java 或业务数据库。"""

import asyncio
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock
import httpx

from app.core.config.common.mcp import McpSettings
from app.core.exception.error_code import ErrorCode
from app.core.exception.exceptions import BusinessException, McpClientError
from app.core.flow_logging import bind_flow_trace
from app.service.tool_execution import ToolExecutor, bind_tool_executor
from app.service.read_only_tool import call_read_only_tool
from app.schemas.chat.tools import DayDetailArguments
from app.helper.mcp_client import JavaMcpClient
from tests.conversation_fakes import MemoryToolCallRepository


def policy(**overrides):
    return McpSettings(_env_file=None, MCP_READ_RETRY_ATTEMPTS=2,
        MCP_READ_RETRY_DELAY_MS=0, MCP_CALL_TIMEOUT_MS=1000,
        MCP_TURN_MAX_CALLS=12, MCP_TURN_BUDGET_MS=60000, **overrides)


class ToolExecutionTests(unittest.IsolatedAsyncioTestCase):
    def executor(self, settings=None, repository=None):
        self.repository = repository or MemoryToolCallRepository()
        return ToolExecutor(settings=settings or policy(), repository_factory=lambda: self.repository,
                            user_id=7, session_id="s", turn_id="a" * 32)

    async def test_retry_has_two_attempts_one_logical_call_and_private_logs(self):
        mcp = SimpleNamespace(call_tool=AsyncMock(side_effect=[
            McpClientError("JAVA_TIMEOUT", "private-error", retryable=True), {"saved": True}]))
        executor = self.executor()
        with bind_flow_trace(7, "s") as trace, self.assertLogs("app.tool", level="INFO") as logs:
            executor.turn_id = trace.turn_id
            result = await executor.execute(mcp, "queryDayDetail", {"secret": "private-input"},
                "private-token", read_only=True, logical_call_id="logical")
            self.assertEqual(2, len(trace.tool_calls))
        self.assertEqual({"saved": True}, result)
        rows = list(self.repository.rows.values())
        self.assertEqual(["timeout", "success"], [r["status"] for r in rows])
        self.assertEqual([1, 2], [r["attempt_no"] for r in rows])
        self.assertEqual(1, len({r["logical_call_id"] for r in rows}))
        self.assertEqual(2, len({r["attempt_id"] for r in rows}))
        self.assertEqual(["start", "timeout", "start", "success"], [r.tool_event for r in logs.records])
        for value in ("private-input", "private-token", "private-error"):
            self.assertNotIn(value, " ".join(logs.output))
            self.assertNotIn(value, str(rows))

    async def test_retry_consumes_turn_call_limit_across_services(self):
        executor = self.executor(policy().model_copy(update={"turn_max_calls": 2}))
        mcp = SimpleNamespace(call_tool=AsyncMock(side_effect=[
            McpClientError("JAVA_TIMEOUT", "timeout", retryable=True), {"date": "2026-10-26", "todos": []}]))
        with bind_tool_executor(executor):
            await call_read_only_tool(mcp, "queryDayDetail", DayDetailArguments(date="2026-10-26"), "token")
            with self.assertRaises(BusinessException) as caught:
                await executor.execute(mcp, "queryTodoList", {}, "token", read_only=True)
        self.assertEqual(ErrorCode.TOOL_CALL_LIMIT.code, caught.exception.code)
        self.assertEqual(2, mcp.call_tool.await_count)
        self.assertEqual(2, executor.count)

    async def test_cumulative_budget_shortens_timeout_and_blocks_next_request(self):
        executor = self.executor(policy().model_copy(update={"turn_budget_ms": 15, "read_retry_attempts": 1}))
        async def slow(*_):
            await asyncio.Event().wait()
        mcp = SimpleNamespace(call_tool=AsyncMock(side_effect=slow))
        with self.assertRaises(McpClientError):
            await executor.execute(mcp, "queryDayDetail", {}, "token", read_only=True)
        with self.assertRaises(BusinessException) as caught:
            await executor.execute(mcp, "queryDayDetail", {}, "token", read_only=True)
        self.assertEqual(ErrorCode.TOOL_BUDGET_EXHAUSTED.code, caught.exception.code)
        self.assertEqual(1, mcp.call_tool.await_count)
        self.assertEqual(15, list(self.repository.rows.values())[0]["timeout_ms"])

    async def test_parallel_requests_reserve_budget_before_dispatch(self):
        executor = self.executor(policy().model_copy(update={"turn_budget_ms": 2000, "call_timeout_ms": 1000}))
        entered, release = asyncio.Event(), asyncio.Event()
        count = 0
        async def query(*_):
            nonlocal count
            count += 1
            if count == 2:
                entered.set()
            await release.wait()
            return {}
        mcp = SimpleNamespace(call_tool=AsyncMock(side_effect=query))
        tasks = [asyncio.create_task(executor.execute(mcp, "queryDayDetail", {}, "token", read_only=True))
                 for _ in range(2)]
        await asyncio.wait_for(entered.wait(), 1)
        self.assertEqual(2000, executor.reserved_ms)
        with self.assertRaises(BusinessException):
            await executor.execute(mcp, "queryDayDetail", {}, "token", read_only=True)
        release.set()
        await asyncio.gather(*tasks)
        self.assertEqual(2, mcp.call_tool.await_count)
        self.assertEqual(0, executor.reserved_ms)

    async def test_retry_delay_is_subject_to_remaining_budget(self):
        executor = self.executor(policy().model_copy(update={"turn_budget_ms": 10, "read_retry_delay_ms": 20}))
        mcp = SimpleNamespace(call_tool=AsyncMock(side_effect=McpClientError("JAVA_UNAVAILABLE", "failed", retryable=True)))
        with self.assertRaises(BusinessException) as caught:
            await executor.execute(mcp, "queryDayDetail", {}, "token", read_only=True)
        self.assertEqual(ErrorCode.TOOL_BUDGET_EXHAUSTED.code, caught.exception.code)
        self.assertEqual(1, mcp.call_tool.await_count)

    async def test_write_timeout_is_persisted_before_dispatch_and_never_retried(self):
        executor = self.executor()
        marks = []
        executor.mark_write_started = lambda: marks.append(True)
        async def write(*_):
            row = next(iter(self.repository.rows.values()))
            self.assertEqual(("running", "uncertain"), (row["status"], row["outcome"]))
            raise McpClientError("JAVA_TIMEOUT", "timeout", retryable=True)
        mcp = SimpleNamespace(call_tool=AsyncMock(side_effect=write))
        with self.assertRaises(McpClientError):
            await executor.execute(mcp, "createTodo", {"title": "secret"}, "token", read_only=False)
        row = next(iter(self.repository.rows.values()))
        self.assertEqual(("timeout", "uncertain"), (row["status"], row["outcome"]))
        self.assertEqual(1, len(marks))
        self.assertEqual(1, mcp.call_tool.await_count)
        with self.assertRaises(BusinessException):
            await executor.execute(mcp, "createTodo", {"title": "secret"}, "token", read_only=False)
        self.assertEqual(1, mcp.call_tool.await_count)

    async def test_record_start_failure_prevents_write_and_write_marker(self):
        repository = SimpleNamespace(start=AsyncMock(side_effect=RuntimeError("DB failed")))
        executor = self.executor(repository=repository)
        marker = []
        executor.mark_write_started = lambda: marker.append(True)
        mcp = SimpleNamespace(call_tool=AsyncMock())
        with self.assertRaises(RuntimeError):
            await executor.execute(mcp, "createTodo", {}, "token", read_only=False)
        mcp.call_tool.assert_not_awaited()
        self.assertEqual([], marker)
        self.assertEqual((0, 0), (executor.count, executor.reserved_ms))

    async def test_finish_failure_does_not_report_write_success(self):
        repository = MemoryToolCallRepository()
        repository.finish = AsyncMock(side_effect=RuntimeError("DB failed"))
        executor = self.executor(repository=repository)
        with self.assertRaises(BusinessException) as caught:
            await executor.execute(SimpleNamespace(call_tool=AsyncMock(return_value={})),
                                   "createTodo", {}, "token", read_only=False)
        self.assertEqual(ErrorCode.TOOL_RECORD_FAILED.code, caught.exception.code)
        self.assertEqual("uncertain", next(iter(repository.rows.values()))["outcome"])

    async def test_cancelled_write_records_exactly_one_terminal_and_retains_uncertainty(self):
        executor = self.executor()
        started = asyncio.Event()
        async def write(*_):
            started.set()
            await asyncio.Event().wait()
        task = asyncio.create_task(executor.execute(SimpleNamespace(call_tool=write),
            "createTodo", {}, "token", read_only=False))
        await started.wait()
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertEqual(1, len(executor.calls))
        self.assertEqual(("cancelled", "uncertain"),
                         (executor.calls[0]["status"], executor.calls[0]["outcome"]))
        self.assertEqual("cancelled", next(iter(self.repository.rows.values()))["status"])

    async def test_invalid_result_is_error_without_retry(self):
        executor = self.executor()
        mcp = SimpleNamespace(call_tool=AsyncMock(return_value={"date": "2026-10-25", "todos": []}))
        with bind_tool_executor(executor), self.assertRaises(McpClientError):
            await call_read_only_tool(mcp, "queryDayDetail", DayDetailArguments(date="2026-10-26"), "token")
        mcp.call_tool.assert_awaited_once()
        self.assertEqual("error", executor.calls[0]["status"])

    async def test_http_timeout_uses_effective_attempt_timeout_instead_of_client_default(self):
        import json
        def respond(request):
            self.assertEqual(30, request.extensions["timeout"]["read"])
            payload = json.loads(request.content)
            return httpx.Response(200, json={"jsonrpc": "2.0", "id": payload["id"], "result": {
                "isError": False, "content": [{"type": "text", "text": "{}"}]}})
        executor = self.executor()
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond), timeout=.001) as client:
            mcp = JavaMcpClient(client)
            await executor.execute(mcp, "batchCreateTodos", {}, "token", read_only=False, timeout_ms=30000)

    async def test_repeated_model_call_ids_do_not_merge_separate_logical_calls(self):
        executor = self.executor()
        mcp = SimpleNamespace(call_tool=AsyncMock(return_value={}))
        for _ in range(2):
            await executor.execute(mcp, "queryDayDetail", {}, "token", read_only=True, logical_call_id="same")
        self.assertEqual(2, len({row["logical_call_id"] for row in self.repository.rows.values()}))

    async def test_repeated_cancellation_waits_for_bounded_terminal_save(self):
        repository = MemoryToolCallRepository()
        executor = self.executor(repository=repository)
        entered, save_entered, release = asyncio.Event(), asyncio.Event(), asyncio.Event()
        original_finish = repository.finish
        async def finish(attempt_id, values):
            save_entered.set()
            await release.wait()
            await original_finish(attempt_id, values)
        repository.finish = finish
        async def write(*_):
            entered.set()
            await asyncio.Event().wait()
        task = asyncio.create_task(executor.execute(SimpleNamespace(call_tool=write),
            "createTodo", {}, "token", read_only=False))
        await entered.wait()
        task.cancel()
        await save_entered.wait()
        task.cancel()
        await asyncio.sleep(0)
        task.cancel()
        release.set()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertEqual("cancelled", next(iter(repository.rows.values()))["status"])
        self.assertEqual(1, len(executor.calls))
