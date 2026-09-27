"""Basic conversation orchestration, independent of the HTTP layer."""

from time import perf_counter
from uuid import uuid4

from app.repository.chat import ChatRepository
from app.helper.model_client import ModelClient
from app.schemas.chat import ChatRequest, ChatResult


class ChatService:
    async def reply(self, user_id: int, request: ChatRequest) -> ChatResult:
        started = perf_counter()
        session_id = request.sessionId or str(uuid4())
        repository = ChatRepository()
        history = await repository.recent_messages(user_id, session_id)
        messages = [{"role": "system", "content":
                     "你是日历助手。当前尚未接入日历工具，不要编造用户的待办或日记。"},
                    *history, {"role": "user", "content": request.message}]
        answer = await ModelClient().complete(messages)
        elapsed_ms = int((perf_counter() - started) * 1000)
        await repository.save_round(user_id, session_id, request.message, answer, elapsed_ms)
        return ChatResult(sessionId=session_id, aiResult=answer, responseTimeMs=elapsed_ms)
