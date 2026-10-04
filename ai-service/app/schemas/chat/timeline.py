"""Safe, user-visible milestones for one Agent turn."""

from typing import Literal

from pydantic import BaseModel, Field


class AgentStep(BaseModel):
    id: int = Field(ge=1)
    kind: Literal["agent", "tool", "summary"]
    label: str = Field(max_length=80)
    narration: str | None = Field(default=None, max_length=240)
    resultSummary: str | None = Field(default=None, max_length=160)
    status: Literal["running", "success", "error"]
    elapsedMs: int = Field(ge=0)
    round: int | None = None


def summarize_tool_result(name: str, data: object) -> str:
    """只从已知字段生成简短结果，不展示或保存原始工具响应。"""
    if not isinstance(data, dict):
        return "工具调用成功。"
    if name == "queryDayDetail":
        date = data.get("date")
        todos = data.get("todos")
        if isinstance(date, str) and isinstance(todos, list):
            note = "，有日记" if data.get("dailyNote") else ""
            return f"{date}：{len(todos)} 项待办{note}。"
    if name == "queryTodoList" and isinstance(data.get("todos"), list):
        return f"查询到 {len(data['todos'])} 项待办。"
    if name == "queryMonthCount" and isinstance(data.get("days"), list):
        return f"返回 {len(data['days'])} 天的日程统计。"
    if name == "batchCreateTodos" and type(data.get("createdCount")) is int:
        return f"成功创建 {data['createdCount']} 项待办。"
    if name == "toggleTodoDate" and data.get("status") in (0, 1):
        return "该日待办已完成。" if data["status"] == 1 else "该日待办已改为未完成。"
    if name == "saveDailyNote" and data.get("saved") is True:
        return "日记已保存。"
    return "工具调用成功。"
