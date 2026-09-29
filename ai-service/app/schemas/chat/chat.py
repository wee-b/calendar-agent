"""聊天 HTTP 请求与完整结果的对外字段。"""

from pydantic import BaseModel, Field


class ChatRequest(BaseModel):
    """会话 ID 可省略，由服务生成；用户身份只从 token 获取。"""

    sessionId: str | None = Field(default=None, max_length=64)
    message: str = Field(min_length=1, max_length=10000)


class ChatResult(BaseModel):
    """普通 /chat 返回值，也用作流式 result 事件的数据。"""

    sessionId: str
    aiResult: str
    responseTimeMs: int
