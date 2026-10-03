"""按 index 拼接模型流中的工具调用片段，完整收齐后才允许执行。"""

from typing import Callable

from app.core.exception.error_code import ErrorCode
from app.core.exception.exceptions import BusinessException
from app.schemas.chat.model_stream import (
    AssistantDeltaData,
    AssistantDeltaEvent,
    AssistantMessage,
    ModelDelta,
    StreamEvent,
    ToolCall,
    ToolCallData,
    ToolCallDeltaData,
    ToolCallDeltaEvent,
    ToolCallLifecycleEvent,
)


EventWriter = Callable[[StreamEvent], None]


class StreamResponseAccumulator:
    """同时收集助手正文与多个工具调用，并发送脱敏的前端进度事件。"""

    def __init__(self, round_number: int, writer: EventWriter, *, allowed_tools=None) -> None:
        self.round_number = round_number
        self.writer = writer
        self.allowed_tools = {"queryDayDetail"} if allowed_tools is None else allowed_tools
        self.content: list[str] = []
        self.calls: dict[int, ToolCall] = {}
        self.started: set[int] = set()
        self.reported_chars: dict[int, int] = {}

    def add(self, delta: ModelDelta) -> None:
        """把本次文本或工具片段追加到当前轮的累积结果。"""

        if delta.content:
            self.content.append(delta.content)
            self.writer(AssistantDeltaEvent(
                data=AssistantDeltaData(
                    round=self.round_number, delta=delta.content,
                )
            ))

        for fragment in delta.tool_calls or []:
            index = fragment.index
            call = self.calls.setdefault(index, ToolCall())
            if fragment.id is not None:
                if call.id and call.id != fragment.id:
                    raise BusinessException(ErrorCode.MODEL_TOOL_ID_INVALID)
                call.id = fragment.id
            if fragment.function is not None:
                if fragment.function.name is not None:
                    call.function.name += fragment.function.name
                if fragment.function.arguments is not None:
                    call.function.arguments += fragment.function.arguments
            self._report(index)

    def _report(self, index: int) -> None:
        """仅在识别为已开放工具后报告调用开始和参数接收长度。"""

        call = self.calls[index]
        if not call.id or call.function.name not in self.allowed_tools:
            return
        if index not in self.started:
            self.writer(ToolCallLifecycleEvent(
                event="tool_call_start",
                data=ToolCallData(
                    round=self.round_number, call_id=call.id,
                    tool=call.function.name,
                ),
            ))
            self.started.add(index)
        size = len(call.function.arguments)
        if size > self.reported_chars.get(index, 0):
            self.writer(ToolCallDeltaEvent(data=ToolCallDeltaData(
                round=self.round_number, call_id=call.id, received_chars=size,
            )))
            self.reported_chars[index] = size

    def finish(self) -> AssistantMessage:
        """检查每个调用的 ID/名称齐全，产生下一步可使用的完整助手消息。"""

        calls: list[ToolCall] = []
        for index in sorted(self.calls):
            call = self.calls[index]
            if not call.id or not call.function.name:
                raise BusinessException(ErrorCode.MODEL_TOOL_INCOMPLETE)
            self._report(index)
            if index in self.started:
                self.writer(ToolCallLifecycleEvent(
                    event="tool_call_end",
                    data=ToolCallData(
                        round=self.round_number, call_id=call.id,
                        tool=call.function.name,
                    ),
                ))
            calls.append(call)
        return AssistantMessage(content="".join(self.content), tool_calls=calls or None)
