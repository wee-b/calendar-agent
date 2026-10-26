"""对话界面需要的最小规划状态，不公开资料快照和工具原文。"""

from __future__ import annotations

import json
from hashlib import sha256
from typing import TYPE_CHECKING, Literal

from pydantic import BaseModel, Field

from app.schemas.plan.planning_tools import PlanningRange

if TYPE_CHECKING:
    from app.schemas.statemachine.flow import PendingTask


class PlanningUiState(BaseModel):
    status: Literal["none", "awaiting_answers", "draft_ready"] = "none"
    questions: list[str] = Field(default_factory=list, max_length=3)
    questionSetId: str | None = None
    dateRange: PlanningRange | None = None
    referenceCount: int = Field(default=0, ge=0, le=5)

    @classmethod
    def from_pending(cls, pending: PendingTask, stage: str) -> PlanningUiState:
        if stage not in {"PLAN", "IMAGE"}:
            return cls()
        prepared = pending.planning
        if prepared and prepared.questions:
            identity = json.dumps([pending.task, prepared.questions, prepared.document_ids,
                                   prepared.date_range.model_dump(mode="json") if prepared.date_range else None],
                                  ensure_ascii=False)
            return cls(status="awaiting_answers", questions=prepared.questions,
                       questionSetId=sha256(identity.encode()).hexdigest()[:16],
                       dateRange=prepared.date_range, referenceCount=len(prepared.document_ids))
        if pending.draft_id is not None:
            return cls(status="draft_ready", dateRange=prepared.date_range if prepared else None,
                       referenceCount=len(prepared.document_ids) if prepared else 0)
        return cls()


class ChatSessionState(BaseModel):
    sessionId: str
    flowStage: str = "CHAT"
    planning: PlanningUiState = Field(default_factory=PlanningUiState)
