"""组装用户上下文、运行对话图，并在完整回答产生后保存对话。"""

import asyncio
import logging
from contextlib import aclosing
from typing import AsyncIterator, Awaitable, Callable
from time import perf_counter
from uuid import uuid4
from datetime import datetime
from zoneinfo import ZoneInfo

from app.repository.chat import ChatRepository
from app.repository.chat_session import ChatSessionRepository
from app.core.config.common.chat_stream import get_chat_stream_settings
from app.schemas.chat.history import (
    ChatHistoryItem, ChatHistoryPage, ChatSessionItem, DEFAULT_HISTORY_LIMIT,
)
from app.schemas.statemachine.route_context import RouteContext, RouteContextMessage
from app.service.chat_graph import ChatGraph
from app.schemas.chat import ChatRequest, ChatResult
from app.schemas.chat.chat import NewSessionResult
from app.schemas.chat.messages import TextMessage
from app.schemas.chat.model_stream import (
    ClientEvent, DoneData, DoneEvent, FinalEvent, GraphEvent, PingEvent, ResultEvent,
)
from pydantic import TypeAdapter


_graph_event_adapter = TypeAdapter(GraphEvent)
logger = logging.getLogger(__name__)


def _log_preview(text: str, limit: int) -> str:
    """日志正文限制字符数并保持单行；省略号表示还有未打印的内容。"""
    preview = "".join(" " if char.isspace() else char for char in text[:limit])
    return preview + ("…" if len(text) > limit else "")


def _log_completed_chat(user_text: str, agent_label: str, answer: str) -> None:
    logger.info("对话完成 | 用户输入：%s | 命中agent：%s | 模型回复：%s",
                _log_preview(user_text, 20), agent_label, _log_preview(answer, 50))


async def _events_with_ping(source: AsyncIterator[GraphEvent], interval: float) -> AsyncIterator[GraphEvent | PingEvent]:
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
    def new_session() -> NewSessionResult:
        """首次保存消息时才创建会话元数据。"""
        return NewSessionResult(sessionId=str(uuid4()))

    async def delete_session(self, user_id: int, session_id: str) -> None:
        await ChatRepository().delete_session(user_id, session_id)

    async def delete_last_round(self, user_id: int, session_id: str) -> None:
        await ChatRepository().delete_last_round(user_id, session_id)

    async def get_history(
        self, user_id: int, session_id: str,
        before_id: int | None = None, limit: int = DEFAULT_HISTORY_LIMIT,
    ) -> ChatHistoryPage:
        """页面每次读取有限条消息，页内按旧到新展示。"""
        rows, has_more = await ChatRepository().list_history(
            user_id, session_id, before_id, limit
        )
        items = [
            ChatHistoryItem(
                dialogueId=row.dialogue_id,
                role=row.role,
                content=row.content,
                createTime=row.create_time,
                responseTimeMs=row.response_time_ms,
            )
            for row in reversed(rows)
        ]
        return ChatHistoryPage(
            items=items, hasMore=has_more,
            nextBeforeId=rows[-1].dialogue_id if has_more else None,
        )

    async def list_sessions(self, user_id: int) -> list[ChatSessionItem]:
        """只查询会话元数据，避免读取全部消息正文。"""
        rows = await ChatSessionRepository().list_sessions(user_id)
        return [
            ChatSessionItem(
                sessionId=row.session_id, title=row.title or "新对话",
                createTime=row.create_time, lastMessageTime=row.last_message_time,
                messageCount=row.message_count,
            )
            for row in rows
        ]

    async def get_route_context(
        self, user_id: int, session_id: str,
        current_message: str | None = None, through_id: int | None = None,
    ) -> RouteContext:
        """供 RouteAgent 直接调用；current_message 仅传入尚未入库的本次输入。

        已入库的本次消息使用 through_id 定位上界，不再重复传 current_message。
        """
        rows = await ChatRepository().route_context_messages(user_id, session_id, through_id)
        context = RouteContext(sessionId=session_id)
        for row in rows:
            message = RouteContextMessage(
                dialogueId=row.dialogue_id, role=row.role, content=row.content
            )
            if row.role == "assistant":
                context.previousReply = message
            else:
                context.userMessages.append(message)
        if current_message is not None:
            context.userMessages.append(RouteContextMessage(role="user", content=current_message))
        return context

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
        graph = ChatGraph()
        answer = await graph.run(messages, token)
        elapsed_ms = int((perf_counter() - started) * 1000)
        await repository.save_round(user_id, session_id, request.message, answer, elapsed_ms)
        _log_completed_chat(request.message, graph.agent_label, answer)
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
            graph = ChatGraph()
            async with aclosing(graph.stream(messages, token)) as graph_events:
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
                        _log_completed_chat(request.message, graph.agent_label, answer)
                        yield ResultEvent(data=ChatResult(
                            sessionId=session_id, aiResult=answer,
                            responseTimeMs=elapsed_ms,
                        ))
                        yield DoneEvent(data=DoneData(
                            rounds=rounds, response_time_ms=elapsed_ms,
                        ))
