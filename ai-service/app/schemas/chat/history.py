from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field
from app.schemas.chat.timeline import AgentStep


DEFAULT_HISTORY_LIMIT = 20
MAX_HISTORY_LIMIT = 100


class DocumentReference(BaseModel):
    fileId: int = Field(gt=0)
    fileName: str


class ChatMessageCreate(BaseModel):
    """持久化一条消息；不要求 user 和 assistant 交替出现。"""

    role: Literal["user", "assistant"]
    content: str
    response_time_ms: int | None = Field(default=None, ge=0)
    dispatch_type: str | None = None
    agent_steps: list[AgentStep] | None = None
    document_references: list[DocumentReference] = Field(default_factory=list)


class ChatHistoryItem(BaseModel):
    dialogueId: int
    role: str
    content: str
    createTime: datetime
    responseTimeMs: int | None = None
    dispatchType: str | None = None
    agentSteps: list[AgentStep] = Field(default_factory=list)
    documentReferences: list[DocumentReference] = Field(default_factory=list)


class ChatHistoryPage(BaseModel):
    items: list[ChatHistoryItem] = Field(default_factory=list)
    hasMore: bool = False
    nextBeforeId: int | None = None


class ChatSessionItem(BaseModel):
    sessionId: str
    title: str
    createTime: datetime
    lastMessageTime: datetime | None
    messageCount: int
