

"""集中注册认证、聊天和独立 RAG 检索路由。"""

from fastapi import APIRouter

from app.api.routers.auth_router import router as auth_router
from app.api.routers.chat_router import router as chat_router
from app.api.routers.rag_router import router as rag_router
from app.api.routers.memory_router import router as memory_router


api_router = APIRouter()
api_router.include_router(auth_router)
api_router.include_router(chat_router)
api_router.include_router(rag_router)
api_router.include_router(memory_router)
