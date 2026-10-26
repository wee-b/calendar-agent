from datetime import datetime, timezone

from pydantic import BaseModel, ConfigDict, field_validator


class ToolCallItem(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    attempt_id: str
    logical_call_id: str
    turn_id: str
    agent: str
    tool_name: str
    read_only: bool
    attempt_no: int
    status: str
    outcome: str
    timeout_ms: int
    elapsed_ms: float | None
    error_code: str | None
    started_at: datetime
    finished_at: datetime | None

    @field_validator("started_at", "finished_at")
    @classmethod
    def utc_datetime(cls, value):
        if value is not None and value.tzinfo is None:
            return value.replace(tzinfo=timezone.utc)
        return value


class ToolCallPage(BaseModel):
    items: list[ToolCallItem]
    has_more: bool
    next_before_id: int | None


class ToolMetrics(BaseModel):
    count: int
    success: int
    timeout: int
    error: int
    cancelled: int
    success_rate: float | None
    avg_ms: float | None
    p95_ms: float | None


class NamedToolMetrics(ToolMetrics):
    tool_name: str


class TurnToolMetrics(BaseModel):
    turns_with_calls: int
    avg_calls: float | None
    max_calls: int | None
    logical_count: int


class ToolStats(BaseModel):
    count: int
    running: int
    uncertain: int
    completed: ToolMetrics
    tools: list[NamedToolMetrics]
    per_turn: TurnToolMetrics
