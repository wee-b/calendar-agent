"""模型 API 生命周期和响应时间埋点，不记录密钥、提示词、参数或响应正文。"""

import asyncio
import logging
from time import perf_counter
from uuid import uuid4

import httpx

from app.core.flow_logging import model_trace_context, record_model_call


logger = logging.getLogger("app.model")


class ModelCallTrace:
    """每次调用输出开始和一个终态；流式调用以读到 DONE 且协议检查通过为成功。"""

    def __init__(self, config, operation: str):
        self.call_id = uuid4().hex
        self.config_name = type(config).__name__
        self.provider = getattr(config.model, "provider", "unknown")
        self.model = config.model.name
        self.timeout = config.timeout_seconds
        self.operation = operation
        self.context = model_trace_context()
        self.http_status = None
        self.response_ms = None
        self.first_output_ms = None
        self.usage: dict[str, int] = {}

    def __enter__(self):
        self.started = perf_counter()
        self._write("start", None, 0)
        return self

    def response_received(self, response) -> None:
        # 非流式为完整 HTTP 响应时间，流式为响应头到达时间。
        self.response_ms = round((perf_counter() - self.started) * 1000, 2)
        self.http_status = response.status_code

    def output_received(self, delta) -> None:
        if self.first_output_ms is None and (delta.content or delta.tool_calls):
            self.first_output_ms = round((perf_counter() - self.started) * 1000, 2)

    def capture_usage(self, usage) -> None:
        if not isinstance(usage, dict):
            return
        for name, alias in (("prompt_tokens", "input_tokens"),
                            ("completion_tokens", "output_tokens"), ("total_tokens", "total_tokens")):
            value = usage.get(name, usage.get(alias))
            if type(value) is int and value >= 0:
                self.usage[name] = value

    def __exit__(self, _type, error, _traceback):
        cause = error
        if error is not None and error.__cause__ is not None:
            cause = error.__cause__
        # 外层请求超时或主动断开会取消调用，保留 cancelled，不混记为上游 timeout。
        status = ("success" if error is None else
                  "cancelled" if isinstance(cause, (asyncio.CancelledError, GeneratorExit)) else
                  "timeout" if isinstance(cause, httpx.TimeoutException) else "error")
        elapsed_ms = round((perf_counter() - self.started) * 1000, 2)
        self._write(status, cause, elapsed_ms, error_code=getattr(error, "code", None))
        record_model_call({"call_id": self.call_id, "provider": self.provider, "model": self.model,
                           "operation": self.operation, "phase": self.context.get("flow_step"),
                           "status": status, "http_status": self.http_status,
                           "elapsed_ms": round(elapsed_ms, 2),
                           "response_ms": self.response_ms, "first_output_ms": self.first_output_ms,
                           "usage": dict(self.usage)}, turn_id=self.context.get("turn_id"))
        return False

    def _write(self, status: str, error, elapsed_ms: float, *, error_code=None) -> None:
        event = {"start": "模型API调用开始", "success": "模型API调用成功", "timeout": "模型调用超时",
                 "cancelled": "模型调用已取消", "error": "模型API调用失败"}[status]
        error_type = type(error).__name__ if error is not None else None
        logger.log(logging.INFO if status in {"start", "success"} else logging.WARNING,
            "%s | 调用ID=%s 轮次=%s 阶段=%s | 配置=%s 厂商=%s 模型=%s | 调用=%s 状态=%s "
            "超时配置=%.2f秒 耗时=%.2f秒 HTTP=%s 响应耗时ms=%s 首片段ms=%s token=%s 异常=%s 错误码=%s",
            event, self.call_id[:8], self.context.get("turn_id", "-")[:8], self.context.get("flow_step", "-"),
            self.config_name, self.provider, self.model, self.operation, status,
            self.timeout, elapsed_ms / 1000, self.http_status, self.response_ms,
            self.first_output_ms, self.usage or None, error_type, error_code,
            extra={**self.context, "model_call_id": self.call_id, "model_event": status,
                   "model_provider": self.provider, "model_name": self.model,
                   "model_operation": self.operation, "model_timeout_seconds": self.timeout,
                   "model_elapsed_seconds": elapsed_ms / 1000, "model_elapsed_ms": elapsed_ms,
                   "model_http_status": self.http_status, "model_response_ms": self.response_ms,
                   "model_first_output_ms": self.first_output_ms, "model_usage": dict(self.usage),
                   "model_error_type": error_type, "model_error_code": error_code})
