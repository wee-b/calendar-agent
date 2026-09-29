"""对话图内部使用的消息；发送给模型时才序列化为 OpenAI 消息格式。"""

from typing import Literal

from pydantic import BaseModel, ConfigDict

from app.schemas.chat.model_stream import AssistantMessage


class TextMessage(BaseModel):
    """系统提示、历史用户消息和本轮用户输入共用的纯文本结构。"""

    model_config = ConfigDict(strict=True)

    role: Literal["system", "user"]
    content: str


class ToolMessage(BaseModel):
    """工具执行结果；tool_call_id 对应触发它的模型工具调用。"""

    model_config = ConfigDict(strict=True)

    role: Literal["tool"] = "tool"
    tool_call_id: str
    content: str


ChatMessage = TextMessage | AssistantMessage | ToolMessage
