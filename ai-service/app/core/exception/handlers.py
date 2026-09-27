"""Register handlers for application-defined errors."""

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app.core.exception.exceptions import BusinessException
from app.core.response.utils import fail


def register_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(BusinessException)
    async def business_error_handler(_request: Request, exc: BusinessException) -> JSONResponse:
        return JSONResponse(status_code=exc.status_code,
                            content=fail(code=exc.code, msg=str(exc)))
