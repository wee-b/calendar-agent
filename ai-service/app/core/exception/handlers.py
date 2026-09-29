"""Register handlers for application-defined errors."""

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app.core.exception.exceptions import BusinessException
from app.core.response.utils import fail


def register_exception_handlers(app: FastAPI) -> None:
    """集中注册业务异常的 HTTP 序列化规则。"""

    @app.exception_handler(BusinessException)
    async def business_error_handler(_request: Request, exc: BusinessException) -> JSONResponse:
        # 旧对话/鉴权接口使用 FastAPI 的 detail 外壳；其他业务错误使用统一响应体。
        if exc.legacy_http:
            return JSONResponse(status_code=exc.status_code,
                                content={"detail": str(exc)})
        return JSONResponse(status_code=exc.status_code,
                            content=fail(code=exc.code, msg=str(exc)))
