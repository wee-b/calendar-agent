import uvicorn
from fastapi import FastAPI
from scalar_fastapi import get_scalar_api_reference

from app.auth.router import router as auth_router

app = FastAPI(
    title="Calendar AI Service",
    description="日历 AI 编排服务",
    version="0.1.0",
    docs_url=None,   # 关闭默认 Swagger UI
    redoc_url=None,
)

app.include_router(auth_router)


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
