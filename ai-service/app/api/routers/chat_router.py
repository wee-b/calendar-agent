import asyncio
import json
import logging

from fastapi import APIRouter, HTTPException, Request, Query
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from app.core.exception.exceptions import BusinessException
from app.core.exception.error_code import StreamErrorCode
from app.core.response.utils import success
from app.api.conversation_transport import ConversationTransport
from app.service.chat import ChatService
from app.schemas.chat import ChatRequest
from app.schemas.chat.history import DEFAULT_HISTORY_LIMIT, MAX_HISTORY_LIMIT
from app.schemas.chat.model_stream import ErrorData, ErrorEvent


router = APIRouter(prefix="/chat", tags=["对话"])
logger = logging.getLogger(__name__)


@router.post("/new-session")
async def new_session():
    return success(ChatService.new_session())


@router.delete("/session")
async def delete_session(
    request: Request,
    session_id: str = Query(..., alias="sessionId", min_length=1, max_length=64, pattern=r"\S"),
):
    await ChatService().delete_session(request.state.user_id, session_id)
    return success()


@router.delete("/last-round")
async def delete_last_round(
    request: Request,
    session_id: str = Query(..., alias="sessionId", min_length=1, max_length=64, pattern=r"\S"),
):
    await ChatService().delete_last_round(request.state.user_id, session_id)
    return success()


@router.post("")
async def chat(body: ChatRequest, request: Request):
    result = await ConversationTransport().reply(request.state.user_id, body, request.state.user_token)
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
            async for item in ConversationTransport().stream_reply(
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


@router.get("/history")
async def history(
    request: Request,
    session_id: str = Query(
        ...,
        alias="sessionId",
        min_length=1,
        max_length=64,
        pattern=r"\S",
    ),
    before_id: int | None = Query(None, alias="beforeId", gt=0),
    limit: int = Query(DEFAULT_HISTORY_LIMIT, ge=1, le=MAX_HISTORY_LIMIT),
):
    result = await ChatService().get_history(
        user_id=request.state.user_id,
        session_id=session_id,
        before_id=before_id,
        limit=limit,
    )
    return success(result)


@router.get("/sessions")
async def sessions(request: Request):
    result = await ChatService().list_sessions(
        user_id=request.state.user_id,
    )
    return success(result)


@router.get("/latest", summary="获取当前会话的 RouteAgent 意图判断上下文")
async def latest(
    request: Request,
    session_id: str = Query(..., alias="sessionId", min_length=1, max_length=64, pattern=r"\S"),
    through_id: int | None = Query(None, alias="throughId", gt=0),
):
    result = await ChatService().get_route_context(
        request.state.user_id, session_id, through_id=through_id
    )
    return success(result)
