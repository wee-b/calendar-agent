import uvicorn
from fastapi import FastAPI
from scalar_fastapi import get_scalar_api_reference

from app.api.endpoints import api_router
from app.core.config.logging_config import configure_logging
from app.core.exception.handlers import register_exception_handlers
from app.core.middleware.access_log import access_log
from app.core.middleware.auth import authenticate_request
from app.core.middleware.cors import configure_cors

app = FastAPI(
    title="Calendar AI Service",
    description="日历 AI 编排服务",
    version="0.1.0",
    docs_url=None,   # 关闭默认 Swagger UI
    redoc_url=None,
)

configure_logging()
app.include_router(api_router)
register_exception_handlers(app)
configure_cors(app)
app.middleware("http")(authenticate_request)
app.middleware("http")(access_log)


@app.get("/docs", include_in_schema=False)
async def scalar_docs():
    return get_scalar_api_reference(
        openapi_url=app.openapi_url,
        title=f"{app.title} - API Docs",
    )

@app.get("/health", tags=["系统"], summary="健康检查")
async def health():
    return {"status": "ok"}

if __name__ == "__main__":
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=8001,
        reload=True,
    )
