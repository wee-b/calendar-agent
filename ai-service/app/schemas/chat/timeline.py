"""Safe, user-visible milestones for one Agent turn."""

from typing import Literal

from pydantic import BaseModel, Field
from app.schemas.rag import QdrantPoint


class AgentStep(BaseModel):
    id: int = Field(ge=1)
    kind: Literal["agent", "tool", "summary"]
    label: str = Field(max_length=80)
    narration: str | None = Field(default=None, max_length=240)
    resultSummary: str | None = Field(default=None, max_length=160)
    status: Literal["running", "success", "error"]
    elapsedMs: int = Field(ge=0)
    round: int | None = None


def summarize_document_results(hits: list[QdrantPoint]) -> str:
    """展示命中数量、少量来源及首条摘录，不增加模型调用。"""
    if not hits:
        return "未找到相关文档片段，将根据本轮需求继续规划。"

    def brief(value: str, limit: int) -> str:
        value = " ".join(value.split())
        return value if len(value) <= limit else value[:limit - 1] + "…"

    sources = list(dict.fromkeys(hit.payload.source for hit in hits if hit.payload.source))
    source_text = "、".join(brief(source, 28) for source in sources[:2]) or "引用文档"
    if len(sources) > 2:
        source_text += "等"
    summary = f"找到 {len(hits)} 个相关片段，来源：{source_text}。"
    excerpt = brief(hits[0].payload.text, 60)
    if excerpt:
        summary += f"片段摘录：{excerpt}"
    return summary[:160]


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
