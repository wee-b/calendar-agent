"""Standalone authenticated RAG search for verification and future use."""

from functools import lru_cache

from fastapi import APIRouter, Depends

from app.core.response.utils import success
from app.schemas.rag import RagSearchRequest
from app.service.rag import RagService

router = APIRouter(prefix="/rag", tags=["检索"])


@lru_cache
def get_rag_service() -> RagService:
    return RagService()


@router.post("/search")
async def search(body: RagSearchRequest, service: RagService = Depends(get_rag_service)):
    return success(await service.search(body.query))
