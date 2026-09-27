

"""Aggregate public API routers."""

from fastapi import APIRouter

from app.api.routers.auth_router import router as auth_router
from app.api.routers.chat_router import router as chat_router


api_router = APIRouter()
api_router.include_router(auth_router)
api_router.include_router(chat_router)
