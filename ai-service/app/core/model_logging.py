"""模型请求中断时记录实际模型信息，不记录密钥、提示词或异常原文。"""

import asyncio
import logging
from time import perf_counter


logger = logging.getLogger("app.model")


def log_model_interruption(config, operation: str, started: float, error: BaseException) -> None:
    model = config.model
    elapsed = perf_counter() - started
    # 总请求超时和客户端断开都可能取消上游调用，不能一概记为模型自身超时。
    event = "模型调用已取消" if isinstance(error, asyncio.CancelledError) else "模型调用超时"
    logger.warning(
        "%s | 配置=%s 厂商=%s 模型=%s | 调用=%s 超时配置=%.2f秒 耗时=%.2f秒 异常=%s",
        event, type(config).__name__, model.provider, model.name,
        operation, config.timeout_seconds, elapsed, type(error).__name__,
        extra={"model_provider": model.provider, "model_name": model.name,
               "model_operation": operation, "model_timeout_seconds": config.timeout_seconds,
               "model_elapsed_seconds": elapsed, "model_error_type": type(error).__name__},
    )
