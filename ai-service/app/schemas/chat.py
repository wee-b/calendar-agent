from pydantic import BaseModel, Field


class ChatRequest(BaseModel):
    sessionId: str | None = Field(default=None, max_length=64)
    message: str = Field(min_length=1, max_length=10000)


class ChatResult(BaseModel):
    sessionId: str
    aiResult: str
    responseTimeMs: int
