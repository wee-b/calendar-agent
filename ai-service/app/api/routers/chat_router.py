import asyncio
import json
import logging

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from app.core.exception.exceptions import BusinessException
from app.core.exception.error_code import StreamErrorCode
from app.core.response.utils import success
from app.service.chat import ChatService
from app.schemas.chat import ChatRequest
from app.schemas.chat.model_stream import ErrorData, ErrorEvent


router = APIRouter(prefix="/chat", tags=["对话"])
logger = logging.getLogger(__name__)


@router.post("")
async def chat(body: ChatRequest, request: Request):
    result = await ChatService().reply(request.state.user_id, body, request.state.user_token)
    return success(result)


def _sse(item: BaseModel) -> str:
    """只在 HTTP 边界序列化事件，使用 schema 中的既有字段别名。"""

    data = item.model_dump(mode="json", by_alias=True, exclude_none=True)
    return f"event: {data['event']}\ndata: {json.dumps(data['data'], ensure_ascii=False)}\n\n"


@router.post("/stream")
async def chat_stream(body: ChatRequest, request: Request):
    """流开始后的错误只能通过 error 事件返回，不能再修改 HTTP 状态。"""

    user_id = request.state.user_id
    token = request.state.user_token

    async def generate():
        try:
            async for item in ChatService().stream_reply(
                user_id, body, token, request.is_disconnected
            ):
                yield _sse(item)
        except asyncio.CancelledError:
            raise
        except TimeoutError:
            yield _sse(ErrorEvent(data=ErrorData(
                code=StreamErrorCode.CHAT_TIMEOUT.code,
                message=StreamErrorCode.CHAT_TIMEOUT.message)))
        except BusinessException as exc:
            yield _sse(ErrorEvent(data=ErrorData(
                code=exc.stream_code, message=str(exc), status=exc.status_code)))
        except HTTPException as exc:
            yield _sse(ErrorEvent(data=ErrorData(
                code=StreamErrorCode.CHAT_ERROR.code, message=str(exc.detail),
                status=exc.status_code)))
        except Exception:
            logger.exception("流式对话失败")
            yield _sse(ErrorEvent(data=ErrorData(
                code=StreamErrorCode.CHAT_ERROR.code,
                message=StreamErrorCode.CHAT_ERROR.message)))

    return StreamingResponse(
        generate(), media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
