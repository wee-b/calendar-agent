"""模型流片段与聊天 SSE 事件的固定结构。"""

from typing import Literal

from pydantic import BaseModel, Field, ConfigDict

from app.schemas.chat.chat import ChatResult


class FunctionFragment(BaseModel):
    """一次增量里的函数名和参数字符串，可能只包含其中一部分。"""

    model_config = ConfigDict(strict=True)

    name: str | None = None
    arguments: str | None = None


class ToolCallFragment(BaseModel):
    """index 标识同一轮流式输出中的第几个工具调用，用于跨片段拼接。"""

    model_config = ConfigDict(strict=True)

    index: int = Field(ge=0)
    id: str | None = None
    function: FunctionFragment | None = None


class ModelDelta(BaseModel):
    """模型 SSE 的 delta；文本和工具调用片段可以分别到达。"""

    model_config = ConfigDict(strict=True)

    content: str | None = None
    tool_calls: list[ToolCallFragment] | None = None

class ToolFunction(BaseModel):
    name: str = ""
    arguments: str = ""


class ToolCall(BaseModel):
    """拼接完成的模型工具调用，可直接写回下一轮模型上下文。"""

    id: str = ""
    type: Literal["function"] = "function"
    function: ToolFunction = Field(default_factory=ToolFunction)


class AssistantMessage(BaseModel):
    """模型的一轮完整消息：可以是正文，也可以包含待执行的工具调用。"""

    model_config = ConfigDict(strict=True)

    role: Literal["assistant"] = "assistant"
    content: str | None = None
    tool_calls: list[ToolCall] | None = None

class ToolCallDeltaData(BaseModel):
    """只公开已接收的参数字符数，不把原始工具参数发给前端。"""

    # 内部用 snake_case 构造，SSE 用既有的 camelCase 字段名；也要能解析已序列化事件。
    model_config = ConfigDict(populate_by_name=True)

    round: int
    call_id: str = Field(alias="callId")
    received_chars: int = Field(alias="receivedChars")  # 已接收长度，不包含参数内容。


class ToolCallData(BaseModel):
    """工具调用开始和结束共用的数据结构。"""

    model_config = ConfigDict(populate_by_name=True)

    round: int
    call_id: str = Field(alias="callId")
    tool: str

class AssistantDeltaData(BaseModel):
    round: int
    delta: str


class AssistantDeltaEvent(BaseModel):
    event: Literal["assistant_delta"] = "assistant_delta"
    data: AssistantDeltaData


class ToolCallDeltaEvent(BaseModel):
    event: Literal["tool_call_delta"] = "tool_call_delta"
    data: ToolCallDeltaData


class ToolCallLifecycleEvent(BaseModel):
    event: Literal["tool_call_start", "tool_call_end"]
    data: ToolCallData


class AgentStatusData(BaseModel):
    agent: Literal["chat", "route", "planner", "executor", "image"] = "chat"
    round: int
    stage: Literal["model", "tool"]


class AgentStatusEvent(BaseModel):
    event: Literal["agent_status"] = "agent_status"
    data: AgentStatusData


class ToolResultData(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    call_id: str = Field(alias="callId")
    status: Literal["success", "error"]
    code: str | None = None


class ToolResultEvent(BaseModel):
    event: Literal["tool_result"] = "tool_result"
    data: ToolResultData


class FinalData(BaseModel):
    answer: str
    rounds: int


class FinalEvent(BaseModel):
    """图内部的完成信号，由 ChatNode 消费，不直接发给前端。"""

    event: Literal["_final"] = "_final"
    data: FinalData


class EmptyData(BaseModel):
    pass


class PingEvent(BaseModel):
    """模型或工具暂时无输出时发送的保活事件。"""

    event: Literal["ping"] = "ping"
    data: EmptyData = Field(default_factory=EmptyData)


class ResultEvent(BaseModel):
    """完整回答已落库后发送，字段与普通 /chat 响应中的 data 一致。"""

    event: Literal["result"] = "result"
    data: ChatResult


class DoneData(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    rounds: int
    response_time_ms: int = Field(alias="responseTimeMs")


class DoneEvent(BaseModel):
    """一次流式请求正常结束的最后一个事件。"""

    event: Literal["done"] = "done"
    data: DoneData


class ErrorData(BaseModel):
    code: str | int
    message: str
    status: int | None = None  # 仅错误具有确定的 HTTP 对应状态时发送。


class ErrorEvent(BaseModel):
    """流已经开始后无法再改变 HTTP 状态，错误通过该 SSE 事件传递。"""

    event: Literal["error"] = "error"
    data: ErrorData


StreamEvent = (AssistantDeltaEvent | ToolCallDeltaEvent | ToolCallLifecycleEvent
               | AgentStatusEvent | ToolResultEvent)
# 图输出包括仅供服务层消费的 _final；客户端输出包含 ping/result/done/error。
GraphEvent = StreamEvent | FinalEvent
ClientEvent = StreamEvent | PingEvent | ResultEvent | DoneEvent | ErrorEvent
