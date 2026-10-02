from enum import Enum

from pydantic import BaseModel, Field


class UserSignal(Enum):
    (
        NEW_CHAT,
        NEW_QUERY,
        NEW_PLAN,
        NEW_EXECUTE,

        CONFIRM,
        REJECT,
        MODIFY,
        UNKNOWN,
        SYNC_PLAN,
        GENERATE_PLAN_IMAGE
    ) = range(1,11)


class ConversationStage(Enum):
    (
        CHAT,
        PLAN,
        EXECUTE,
        IMAGE
    ) = range(1,5)


class ConversationSnapshot(BaseModel):
    """读取会话后，提供给本轮流程使用的状态快照。"""

    task: str | None = None
    draft_id: int | None = None
    plan_preview: str | None = None
    image_instruction: str | None = None

    version: int = Field(default=0, ge=0)
    processing: bool = False


class PendingTask(BaseModel):
    """会话中需要保留的任务内容。"""

    task: str | None = None
    draft_id: int | None = None
    plan_preview: str | None = None
    image_instruction: str | None = None


class ConversationTurnInput(BaseModel):
    """服务端整理好后，交给会话主图的本轮输入。"""

    user_id: int = Field(gt=0)
    session_id: str = Field(min_length=1)
    message: str = Field(min_length=1)

    previous_reply: str | None = None
    snapshot: ConversationSnapshot



class AgentTurnResult(BaseModel):
    """节点完成本轮处理后，交给主流程的结果。"""

    reply: str = Field(min_length=1)
    completed: bool
    pending: PendingTask
    dispatch_type: str








