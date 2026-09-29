"""业务和外部服务异常；HTTP/SSE 层再决定如何输出。"""

from app.core.exception.error_code import ErrorCode, StreamErrorCode


class BusinessException(Exception):
    """携带统一错误定义，必要时兼容既有 HTTP detail 和 SSE 错误码。"""

    def __init__(self, error: ErrorCode, *, message: str | None = None) -> None:
        super().__init__(message or error.message)
        self.code = error.code
        self.status_code = error.status_code
        self.legacy_http = error.legacy_http
        self.stream_code = StreamErrorCode.CHAT_ERROR.code if error.legacy_http else error.code


class McpClientError(Exception):
    """Java 工具调用失败；只有 retryable=True 才允许只读链路重试。"""

    def __init__(self, code: str, message: str, *, retryable: bool = False) -> None:
        super().__init__(message)
        self.code = code
        self.retryable = retryable


class QdrantError(RuntimeError):
    pass


class EmbeddingError(RuntimeError):
    pass
