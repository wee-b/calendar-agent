"""普通对话节点：组装模型上下文、运行 ChatGraph 并保存完整回复。"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import aclosing
from datetime import datetime
from time import perf_counter
from uuid import uuid4
from zoneinfo import ZoneInfo

from pydantic import TypeAdapter

from app.all_graph.chat_graph import ChatGraph
from app.core.config.common.chat_stream import get_chat_stream_settings
from app.schemas.chat import ChatRequest, ChatResult
from app.schemas.chat.messages import TextMessage
from app.schemas.chat.model_stream import (
    ClientEvent, DoneData, DoneEvent, FinalEvent, GraphEvent, PingEvent, ResultEvent,
)
from app.schemas.statemachine.flow import AgentTurnResult, ConversationStage, PendingTask, UserSignal
from app.service.chat import ChatService


_graph_event_adapter = TypeAdapter(GraphEvent)
logger = logging.getLogger(__name__)


def _log_preview(text: str, limit: int) -> str:
    preview = "".join(" " if char.isspace() else char for char in text[:limit])
    return preview + ("…" if len(text) > limit else "")


def _log_completed_chat(user_text: str, agent_label: str, answer: str) -> None:
    logger.info("对话完成 | 用户输入：%s | 命中agent：%s | 模型回复：%s",
                _log_preview(user_text, 20), agent_label, _log_preview(answer, 50))


async def _events_with_ping(
    source: AsyncIterator[GraphEvent], interval: float,
) -> AsyncIterator[GraphEvent | PingEvent]:
    """模型或工具暂时没有事件时发 ping，不取消正在等待的图事件。"""
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


class ChatNode:
    """调用模型与工具；会话历史的读写交给 ChatService。"""

    def __init__(self, chat_service: ChatService | None = None) -> None:
        self.chat_service = chat_service if chat_service is not None else ChatService()

    async def _context(self, user_id: int, session_id: str, message: str):
        """在持久化历史之后追加当天提示和本轮尚未入库的输入。"""
        history = await self.chat_service.recent_messages(user_id, session_id)
        today = datetime.now(ZoneInfo("Asia/Shanghai")).date().isoformat()
        return [TextMessage(role="system", content=
                f"你是日历助手。今天是北京时间 {today}。"
                "需要查询某一天的日程时调用 queryDayDetail。"
                "系统支持查询日程，创建、修改和删除日程与待办，更新完成状态，保存日记，制定并同步规划，以及生成规划示意图。写操作需用户确认后执行。"
                "用户询问你能做什么时，以整个系统的能力回答；当前模型只收到查询工具，不表示系统只有查询功能，不要说自己无法直接修改日程或让用户手动完成。"
                "不要编造用户的待办或日记。"),
                *history, TextMessage(role="user", content=message)]

    async def reply(
        self, user_id: int, request: ChatRequest, token: str = "",
        *, before_save: Callable[[], None] | None = None,
    ) -> ChatResult:
        """非流式对话；只有完整模型回答产生后才保存消息。"""
        started = perf_counter()
        session_id = request.sessionId or str(uuid4())
        messages = await self._context(user_id, session_id, request.message)
        graph = ChatGraph()
        answer = await graph.run(messages, token)
        elapsed_ms = int((perf_counter() - started) * 1000)
        if before_save is not None:
            before_save()
        await self.chat_service.save_round(
            user_id, session_id, request.message, answer, elapsed_ms,
        )
        _log_completed_chat(request.message, graph.agent_label, answer)
        return ChatResult(sessionId=session_id, aiResult=answer, responseTimeMs=elapsed_ms)

    async def stream_reply(
        self, user_id: int, request: ChatRequest, token: str,
        is_disconnected: Callable[[], Awaitable[bool]],
    ) -> AsyncIterator[ClientEvent]:
        """流式对话；收到图的最终事件且连接仍在时才保存本轮消息。"""
        started = perf_counter()
        settings = get_chat_stream_settings()
        async with asyncio.timeout(settings.request_timeout_seconds):
            session_id = request.sessionId or str(uuid4())
            messages = await self._context(user_id, session_id, request.message)
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
                        await self.chat_service.save_round(
                            user_id, session_id, request.message, answer, elapsed_ms,
                        )
                        _log_completed_chat(request.message, graph.agent_label, answer)
                        yield ResultEvent(data=ChatResult(
                            sessionId=session_id, aiResult=answer,
                            responseTimeMs=elapsed_ms,
                        ))
                        yield DoneEvent(data=DoneData(
                            rounds=rounds, response_time_ms=elapsed_ms,
                        ))

    async def __call__(self, state: dict, context) -> AgentTurnResult:
        """主图调用时只返回回答；历史和阶段在 commit 节点的同一事务保存。"""
        signal = state["signal"]
        if signal == UserSignal.REJECT:
            return AgentTurnResult(reply="已取消当前任务。你可以继续告诉我新的需求。", completed=True,
                                   pending=PendingTask(), dispatch_type="CANCEL")
        if signal not in {UserSignal.NEW_CHAT, UserSignal.NEW_QUERY}:
            prompts = {
                ConversationStage.CHAT: "当前没有待处理任务，请说明你想查询、规划或执行什么操作。",
                ConversationStage.PLAN: "当前正在处理规划。可以补充条件、同步已有草稿、生成示意图，或取消。",
                ConversationStage.EXECUTE: "当前有待执行内容，请确认、修改或取消。生成图片和同步规划需要先处理这项任务。",
                ConversationStage.IMAGE: "图片和原规划均已保留。可以说明图片修改要求、同步原规划，或取消。",
            }
            return AgentTurnResult(reply=prompts[state["stage"]], completed=False,
                                   pending=state["pending"], dispatch_type="PENDING_UNKNOWN")
        messages = await self._context(state["user_id"], state["session_id"], state["message"])
        graph = ChatGraph()
        context.agent_label = graph.agent_label
        if context.emit is None:
            answer = await graph.run(messages, context.token)
        else:
            answer = None
            async with aclosing(graph.stream(messages, context.token)) as events:
                async for event in events:
                    if isinstance(event, FinalEvent):
                        answer = event.data.answer
                        context.rounds = event.data.rounds
                    else:
                        await context.send(event)
            if not answer:
                from app.core.exception.error_code import ErrorCode
                from app.core.exception.exceptions import BusinessException
                raise BusinessException(ErrorCode.CHAT_NO_FINAL)
        return AgentTurnResult(
            reply=answer, completed=True, pending=state["pending"],
            dispatch_type="QUERY" if signal == UserSignal.NEW_QUERY else "CHAT",
        )
