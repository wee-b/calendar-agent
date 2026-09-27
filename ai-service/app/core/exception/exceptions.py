"""Business exceptions raised outside the HTTP layer."""

from app.core.exception.error_code import ErrorCode


class BusinessException(Exception):
    def __init__(self, message: str, code: int = ErrorCode.BUSINESS_ERROR,
                 status_code: int = 400) -> None:
        super().__init__(message)
        self.code = code
        self.status_code = status_code
