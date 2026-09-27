from fastapi import APIRouter, Request

from app.core.response.utils import success
from app.service.chat import ChatService
from app.schemas.chat import ChatRequest


router = APIRouter(prefix="/chat", tags=["对话"])


@router.post("")
async def chat(body: ChatRequest, request: Request):
    result = await ChatService().reply(request.state.user_id, body)
    return success(result)
