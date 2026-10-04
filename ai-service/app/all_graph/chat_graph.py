"""用 LangGraph 在模型回答与 Java 只读工具之间循环，最多调用三轮模型。"""

from __future__ import annotations

import json
from contextlib import aclosing
from datetime import date
from typing import AsyncIterator, TypedDict

from langgraph.config import get_stream_writer
from langgraph.graph import END, START, StateGraph
from pydantic import TypeAdapter, ValidationError

from app.core.exception.error_code import ErrorCode
from app.core.exception.exceptions import BusinessException
from app.core.flow_logging import log_turn_step
from app.helper.mcp_client import JavaMcpClient, McpClientError
from app.helper.model_client import ModelClient
from app.schemas.chat.messages import ChatMessage, ToolMessage
from app.schemas.chat.model_stream import (
    AgentStatusData, AgentStatusEvent, AssistantMessage, FinalData, FinalEvent,
    GraphEvent, ModelDelta, StreamEvent, ToolResultData, ToolResultEvent,
)
from app.schemas.chat.tools import (
    DAY_DETAIL_TOOL, DAY_DETAIL_TOOL_NAME, MAX_MODEL_ROUNDS, DayDetailArguments,
)
from app.schemas.chat.timeline import summarize_tool_result
from app.service.model_stream import StreamResponseAccumulator
from app.service.read_only_tool import call_read_only_tool


class ChatState(TypedDict):
    """图在节点之间传递的完整状态；messages 始终保留已执行的工具结果。"""

    messages: list[ChatMessage]
    rounds: int  # 已完成的模型调用次数，不计 Java 工具调用。
    answer: str  # 累加各轮助手正文，最终一次性落库。


class ChatStateUpdate(TypedDict, total=False):
    """节点只返回本轮修改的状态字段。"""

    messages: list[ChatMessage]
    rounds: int
    answer: str


_stream_event_adapter = TypeAdapter(StreamEvent)


def _assistant_from_wire(value: object) -> AssistantMessage:
    """把模型客户端或替身返回的消息转换为图内 AssistantMessage。"""
    if isinstance(value, AssistantMessage):
        return value
    try:
        return AssistantMessage.model_validate(value)
    except ValidationError as exc:
        raise BusinessException(ErrorCode.MODEL_MESSAGE_INVALID) from exc


class ChatGraph:
    """对话图负责判断是否调用工具，并把工具结果送入下一轮模型上下文。"""

    def __init__(self, model: ModelClient | None = None,
                 mcp_factory: type[JavaMcpClient] | None = None) -> None:
        self.model = model or ModelClient()
        self.mcp_factory = mcp_factory or JavaMcpClient

    @property
    def agent_label(self) -> str:
        # 自定义模型替身可能没有厂商配置，不以默认厂商冒充实际调用厂商。
        config = getattr(self.model, "config", None)
        provider = config.model.provider if config is not None else "unknown"
        return f"chatAgent({provider})"

    async def run(self, messages: list[ChatMessage], token: str) -> str:
        """非流式入口，返回可落库的完整回答。"""

        result = await self._build_graph(token, streaming=False).ainvoke(
            {"messages": messages, "rounds": 0, "answer": ""}
        )
        return result["answer"]

    async def stream(self, messages: list[ChatMessage], token: str) -> AsyncIterator[GraphEvent]:
        """透传图节点事件，并把最终状态转为仅供 ChatNode 使用的 _final。"""

        final_state: ChatState | None = None
        async with aclosing(self._build_graph(token, streaming=True).astream(
            {"messages": messages, "rounds": 0, "answer": ""},
            stream_mode=["custom", "values"], version="v2",
        )) as parts:
            async for part in parts:
                if part["type"] == "custom":
                    yield _stream_event_adapter.validate_python(part["data"])
                elif part["type"] == "values":
                    final_state = part["data"]
        if final_state is None or not final_state["answer"]:
            raise BusinessException(ErrorCode.CHAT_NO_FINAL)
        yield FinalEvent(data=FinalData(answer=final_state["answer"],
                                        rounds=final_state["rounds"]))

    def _build_graph(self, token: str, *, streaming: bool):
        """两个节点交替运行：模型请求工具时去 tools，否则结束。"""

        async def call_model(state: ChatState) -> ChatStateUpdate:
            log_turn_step("CHAT_MODEL", 模型轮次=state["rounds"] + 1, 模型=self.agent_label)
            # 最后一轮不再声明工具，避免模型生成无法执行的调用。
            tools = [DAY_DETAIL_TOOL] if state["rounds"] < MAX_MODEL_ROUNDS - 1 else None
            wire_messages = [message.model_dump(mode="json", exclude_none=True)
                             for message in state["messages"]]
            if streaming:
                writer = get_stream_writer()
                writer(AgentStatusEvent(data=AgentStatusData(
                    round=state["rounds"] + 1, stage="model",
                )).model_dump(mode="json", by_alias=True))

                def write_event(event: StreamEvent) -> None:
                    writer(event.model_dump(mode="json", by_alias=True))

                accumulator = StreamResponseAccumulator(state["rounds"] + 1, write_event)
                async for delta in self.model.stream_chat(wire_messages, tools=tools):
                    try:
                        parsed_delta = ModelDelta.model_validate(delta)
                    except ValidationError as exc:
                        if any(error["loc"] and error["loc"][0] == "content"
                               for error in exc.errors()):
                            raise BusinessException(ErrorCode.MODEL_TEXT_DELTA_INVALID) from exc
                        raise BusinessException(ErrorCode.MODEL_TOOL_DELTA_INVALID) from exc
                    accumulator.add(parsed_delta)
                response = accumulator.finish()
            else:
                response = _assistant_from_wire(await self.model.chat(wire_messages, tools=tools))
            calls = response.tool_calls
            if calls and not tools:
                raise BusinessException(ErrorCode.MODEL_ROUND_LIMIT)
            if not calls and (not response.content or not response.content.strip()):
                raise BusinessException(ErrorCode.MODEL_EMPTY_ANSWER)
            log_turn_step("CHAT_MODEL_RESULT", 模型轮次=state["rounds"] + 1, 工具数=len(calls or []))
            return {"messages": [*state["messages"], response],
                    "rounds": state["rounds"] + 1,
                    "answer": state["answer"] + (response.content or "")}

        async def call_tools(state: ChatState) -> ChatStateUpdate:
            """先校验模型参数，再逐个调用允许的 Java 工具并生成 tool 消息。"""

            writer = get_stream_writer() if streaming else None
            last = state["messages"][-1]
            calls = last.tool_calls if isinstance(last, AssistantMessage) else None
            if not calls:
                raise BusinessException(ErrorCode.MODEL_TOOL_CALL_INVALID)
            results: list[ToolMessage] = []
            async with self.mcp_factory() as mcp:
                for call in calls:
                    if not call.id:
                        raise BusinessException(ErrorCode.MODEL_TOOL_CALL_INVALID)
                    if call.function.name != DAY_DETAIL_TOOL_NAME:
                        raise BusinessException(ErrorCode.MODEL_TOOL_UNAPPROVED)
                    try:
                        args = DayDetailArguments.model_validate_json(call.function.arguments)
                    except ValidationError as exc:
                        raise BusinessException(ErrorCode.MODEL_TOOL_ARGUMENT_INVALID) from exc
                    try:
                        parsed_date = date.fromisoformat(args.date)
                    except ValueError as exc:
                        raise BusinessException(ErrorCode.MODEL_TOOL_DATE_INVALID) from exc
                    if parsed_date.isoformat() != args.date:
                        raise BusinessException(ErrorCode.MODEL_TOOL_DATE_INVALID)
                    log_turn_step("CHAT_TOOL", 模型轮次=state["rounds"], 工具=call.function.name, call_id=call.id)
                    try:
                        if writer:
                            writer(AgentStatusEvent(data=AgentStatusData(
                                round=state["rounds"], stage="tool",
                            )).model_dump(mode="json", by_alias=True))
                        data = await call_read_only_tool(
                            mcp, DAY_DETAIL_TOOL_NAME, args, token)
                        content = data.model_dump_json(exclude_unset=True)
                        log_turn_step("CHAT_TOOL_RESULT", call_id=call.id, 结果="成功")
                        if writer:
                            writer(ToolResultEvent(data=ToolResultData(
                                call_id=call.id, status="success",
                                summary=summarize_tool_result(DAY_DETAIL_TOOL_NAME, data.model_dump()),
                            )).model_dump(mode="json", by_alias=True, exclude_none=True))
                    except McpClientError as exc:
                        log_turn_step("CHAT_TOOL_RESULT", call_id=call.id, 结果="失败", 错误码=exc.code)
                        # 鉴权错误终止对话；普通工具错误交给模型解释给用户。
                        if exc.code == "AUTH_FAILED":
                            raise BusinessException(ErrorCode.MCP_AUTH_FAILED,
                                                    message=str(exc)) from exc
                        content = json.dumps({"error": exc.code, "message": str(exc)},
                                             ensure_ascii=False)
                        if writer:
                            writer(ToolResultEvent(data=ToolResultData(
                                call_id=call.id, status="error", code=exc.code,
                            )).model_dump(mode="json", by_alias=True, exclude_none=True))
                    results.append(ToolMessage(tool_call_id=call.id, content=content))
            return {"messages": [*state["messages"], *results]}

        def next_step(state: ChatState) -> str:
            last = state["messages"][-1]
            return "tools" if isinstance(last, AssistantMessage) and last.tool_calls else END

        builder = StateGraph(ChatState)
        builder.add_node("model", call_model)
        builder.add_node("tools", call_tools)
        builder.add_edge(START, "model")
        builder.add_conditional_edges("model", next_step, {"tools": "tools", END: END})
        builder.add_edge("tools", "model")
        return builder.compile()
