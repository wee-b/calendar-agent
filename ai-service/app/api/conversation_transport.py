"""HTTP 对话适配层：运行主图并传输进度流。"""

import asyncio
from collections.abc import AsyncIterator
from uuid import uuid4

from app.all_graph.conversation_graph import ConversationGraph
from app.core.config.common.chat_stream import get_chat_stream_settings
from app.schemas.chat import ChatRequest, ChatResult
from app.schemas.chat.model_stream import ClientEvent, DoneData, DoneEvent, PingEvent, ResultEvent


class ConversationTransport:
    """仅处理请求/响应与 SSE；AI 流程和后台调度由 LangGraph 编排。"""
    def __init__(self, graph_factory=None):
        self.graph_factory = graph_factory if graph_factory is not None else ConversationGraph

    async def _run(self, user_id, request, token, *, emit=None, is_disconnected=None):
        session_id = request.sessionId or str(uuid4())
        graph = self.graph_factory(user_id=user_id, session_id=session_id)
        settings = get_chat_stream_settings()
        async with asyncio.timeout(settings.request_timeout_seconds):
            result = await graph.run_turn(request.message, token, emit=emit, is_disconnected=is_disconnected)
        return ChatResult(sessionId=session_id, aiResult=result.reply, responseTimeMs=result.elapsed_ms,
                          dispatchType=result.dispatch_type, currentAgent=result.agent.name,
                          flowStage=result.stage.name), result.rounds

    async def reply(self, user_id: int, request: ChatRequest, token: str):
        result, _ = await self._run(user_id, request, token)
        return result

    async def stream_reply(self, user_id, request, token, is_disconnected) -> AsyncIterator[ClientEvent]:
        # 有界队列将网络背压传给模型/工具节点；取消时关闭整个图及上游流。
        queue = asyncio.Queue(maxsize=32)

        async def produce():
            result, rounds = await self._run(user_id, request, token, emit=queue.put,
                                            is_disconnected=is_disconnected)
            await queue.put(ResultEvent(data=result))
            await queue.put(DoneEvent(data=DoneData(rounds=rounds, response_time_ms=result.responseTimeMs)))

        worker = asyncio.create_task(produce())
        pending = None
        try:
            while not worker.done() or not queue.empty():
                if await is_disconnected():
                    return
                pending = asyncio.create_task(queue.get())
                done, _ = await asyncio.wait({pending, worker},
                                            timeout=get_chat_stream_settings().ping_interval_seconds,
                                            return_when=asyncio.FIRST_COMPLETED)
                if pending in done:
                    event = pending.result()
                    pending = None
                    if await is_disconnected():
                        return
                    yield event
                else:
                    pending.cancel()
                    await asyncio.gather(pending, return_exceptions=True)
                    pending = None
                    if not done:
                        yield PingEvent()
            await worker  # 传播模型/工具异常，HTTP 层编码为 error，绝不补发 done。
        finally:
            if pending is not None:
                pending.cancel()
                await asyncio.gather(pending, return_exceptions=True)
            if not worker.done():
                worker.cancel()
            await asyncio.gather(worker, return_exceptions=True)
