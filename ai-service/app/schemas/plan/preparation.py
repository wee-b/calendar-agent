"""跨轮澄清需要的资料快照；不保存 token 或模型推理文本。"""

import re
from datetime import timedelta

from pydantic import BaseModel, ConfigDict, Field

from app.schemas.plan.plan import today
from app.schemas.plan.planning_tools import PlanningRange, Question, ScheduleRangeResult


class PlanningPreparation(BaseModel):
    model_config = ConfigDict(extra="forbid")

    date_range: PlanningRange | None = None
    document_ids: list[int] = Field(default_factory=list, max_length=5)
    references: str | None = None
    memories: list[dict] | None = None
    schedules: ScheduleRangeResult | None = None
    schedules_at: float | None = None
    questions: list[Question] = Field(default_factory=list, max_length=3)


def infer_planning_range(message: str) -> PlanningRange | None:
    """无额外模型调用的保守回退；复杂相对日期由外层 Route 输出。

    只识别已明确的完整日期、今天/明天/后天、本周/下周及未来N天。
    单独的“学习两周”不擅自补开始日期，留给澄清模型询问。
    """
    matches = re.findall(r"(?<!\d)\d{4}-\d{2}-\d{2}(?!\d)", message)
    if matches:
        # 单个日期后仍带持续时长时，避免误判为单日；优先使用 Route 的完整区间。
        if len(matches) == 1 and re.search(r"\d+\s*(?:天|周|个月)|开始|起", message):
            return None
        try:
            return PlanningRange(startDate=matches[0], endDate=matches[-1])
        except ValueError:
            return None
    day = today()
    week = re.search(r"(下周|本周|这周)(?![一二三四五六日天末])", message)
    if week:
        start = day + timedelta(days=7 - day.weekday()) if week[1] == "下周" else day
        end = start + timedelta(days=6) if week[1] == "下周" else day + timedelta(days=6 - day.weekday())
        return PlanningRange(startDate=start, endDate=end)
    future = re.search(r"(?:未来|接下来)(\d+)天", message)
    if future and 1 <= int(future[1]) <= 181:
        return PlanningRange(startDate=day, endDate=day + timedelta(days=int(future[1]) - 1))
    relative = [(message.find(word), offset) for word, offset in (("今天", 0), ("明天", 1), ("后天", 2))
                if word in message]
    if len(relative) == 1 and re.search(r"从.+(?:开始|起)", message):
        return None
    if relative and not re.search(r"(?:\d+|[一二两三四五六七八九十]+)(?:天|周|个月)", message):
        offsets = [offset for _, offset in sorted(relative)]
        try:
            return PlanningRange(startDate=day + timedelta(days=offsets[0]),
                                 endDate=day + timedelta(days=offsets[-1]))
        except ValueError:
            return None
    return None


def requests_new_period(message: str) -> bool:
    """范围变化但新范围无法展开时，作废旧范围，交给 LLM 重新问清日期。"""
    return bool(re.search(
        r"\d{4}-\d{2}-\d{2}|下个月|明年|月底|月初|下周|本周|这周|从.+(?:开始|起)|"
        r"(?:改|换|推迟|提前|延长|缩短).{0,12}(?:日期|天|周|月)", message))
