"""组装用户上下文、运行对话图，并在完整回答产生后保存对话。"""

import asyncio
from contextlib import aclosing
from typing import AsyncIterator, Awaitable, Callable
from time import perf_counter
from uuid import uuid4
from datetime import datetime
from zoneinfo import ZoneInfo

from app.repository.chat import ChatRepository
from app.core.config.chat_stream import get_chat_stream_settings
from app.service.chat_graph import ChatGraph
from app.schemas.chat import ChatRequest, ChatResult
from app.schemas.chat.messages import TextMessage
from app.schemas.chat.model_stream import (
    ClientEvent, DoneData, DoneEvent, FinalEvent, GraphEvent, PingEvent, ResultEvent,
)
from pydantic import TypeAdapter


_graph_event_adapter = TypeAdapter(GraphEvent)


async def _events_with_ping(
    source: AsyncIterator[GraphEvent], interval: float,
) -> AsyncIterator[GraphEvent | PingEvent]:
    """等待下一条图事件时定期发 ping；同一个待完成任务不能因 ping 被取消。"""

    iterator = source.__aiter__()
    while True:
        pending = asyncio.create_task(anext(iterator))
        try:
            while not pending.done():
                done, _ = await asyncio.wait({pending}, timeout=interval)
                if not done:
                    yield PingEvent()
            try:
                event = pending.result()
            except StopAsyncIteration:
                return
            yield _graph_event_adapter.validate_python(event)
        finally:
            if not pending.done():
                pending.cancel()
                await asyncio.gather(pending, return_exceptions=True)


class ChatService:
    """普通与流式聊天共用同一段历史和持久化规则。"""

    @staticmethod
    async def _context(user_id: int, request: ChatRequest):
        """从用户 ID 和会话 ID 读取历史，再追加当天系统提示与本轮输入。"""

        session_id = request.sessionId or str(uuid4())
        repository = ChatRepository()
        history = await repository.recent_messages(user_id, session_id)
        today = datetime.now(ZoneInfo("Asia/Shanghai")).date().isoformat()
        messages = [TextMessage(role="system", content=
                    f"你是日历助手。今天是北京时间 {today}。"
                    "需要查询某一天的日程时调用 queryDayDetail。"
                    "目前只能读取日期详情，不能创建、修改或删除日程。"
                    "不要编造用户的待办或日记。"),
                    *history, TextMessage(role="user", content=request.message)]
        return session_id, repository, messages

    async def reply(self, user_id: int, request: ChatRequest, token: str = "") -> ChatResult:
        """等待模型完整回答后保存一轮用户消息和助手消息。"""

        started = perf_counter()
        session_id, repository, messages = await self._context(user_id, request)
        answer = await ChatGraph().run(messages, token)
        elapsed_ms = int((perf_counter() - started) * 1000)
        await repository.save_round(user_id, session_id, request.message, answer, elapsed_ms)
        return ChatResult(sessionId=session_id, aiResult=answer, responseTimeMs=elapsed_ms)

    async def stream_reply(
        self, user_id: int, request: ChatRequest, token: str,
        is_disconnected: Callable[[], Awaitable[bool]],
    ) -> AsyncIterator[ClientEvent]:
        """断连或失败时结束流；仅在收到图的 _final 后保存并发送 result/done。"""

        started = perf_counter()
        settings = get_chat_stream_settings()
        async with asyncio.timeout(settings.request_timeout_seconds):
            session_id, repository, messages = await self._context(user_id, request)
            async with aclosing(ChatGraph().stream(messages, token)) as graph_events:
                async with aclosing(_events_with_ping(
                    graph_events, settings.ping_interval_seconds,
                )) as events:
                    async for event in events:
                        if await is_disconnected():
                            return
                        if not isinstance(event, FinalEvent):
                            yield event
                            continue
                        answer = event.data.answer
                        rounds = event.data.rounds
                        elapsed_ms = int((perf_counter() - started) * 1000)
                        await repository.save_round(
                            user_id, session_id, request.message, answer, elapsed_ms)
                        yield ResultEvent(data=ChatResult(
                            sessionId=session_id, aiResult=answer,
                            responseTimeMs=elapsed_ms,
                        ))
                        yield DoneEvent(data=DoneData(
                            rounds=rounds, response_time_ms=elapsed_ms,
                        ))
