"""规划草稿与日历写工具共用的校验结构；身份始终来自服务端。"""

from datetime import date, datetime
from typing import Annotated
from zoneinfo import ZoneInfo

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


WeekDay = Annotated[int, Field(strict=True, ge=1, le=7)]


def today() -> date:
    return datetime.now(ZoneInfo("Asia/Shanghai")).date()


class TodoCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    title: str = Field(min_length=1, max_length=255)
    dayContent: str | None = None
    startDate: date
    endDate: date
    weekDays: list[WeekDay] | None = Field(default=None, min_length=1, max_length=7)
    color: str | None = None

    @field_validator("title")
    @classmethod
    def nonblank(cls, value):
        if not value.strip():
            raise ValueError("待办标题不能为空")
        return value.strip()

    @model_validator(mode="after")
    def validate_range(self):
        if not 0 <= (self.endDate - self.startDate).days <= 180:
            raise ValueError("待办日期范围须在 0 到 180 天之间")
        if self.weekDays is None and self.startDate != self.endDate:
            raise ValueError("跨日待办必须指定 weekDays")
        if self.weekDays:
            length = (self.endDate - self.startDate).days + 1
            if not any((self.startDate.weekday() + offset) % 7 + 1 in self.weekDays
                       for offset in range(min(length, 7))):
                raise ValueError("日期范围内没有选中的执行日")
        return self


class PlanTodo(TodoCreate):
    weekDays: list[WeekDay] = Field(min_length=1, max_length=7)
    reason: str | None = None

    def create_arguments(self) -> dict:
        import re

        result = self.model_dump(mode="json", exclude={"reason"})
        result["color"] = self.color if re.fullmatch(r"#[0-9a-fA-F]{6}", self.color or "") else "#5c4b37"
        result["dayContent"] = self.dayContent or self.title
        return result


class Plan(BaseModel):
    model_config = ConfigDict(extra="forbid")

    goal: str | None = Field(default=None, max_length=255)
    startDate: date | None = None
    endDate: date | None = None
    analysis: str | None = None
    todos: list[PlanTodo] = Field(min_length=1, max_length=100)

    @model_validator(mode="after")
    def validate_dates(self):
        if self.startDate and self.endDate and self.startDate > self.endDate:
            raise ValueError("规划日期范围无效")
        for item in self.todos:
            if self.startDate and item.startDate < self.startDate:
                raise ValueError("待办早于规划开始日期")
            if self.endDate and item.endDate > self.endDate:
                raise ValueError("待办晚于规划结束日期")
        return self

    def validate_current_year(self):
        if any(item.startDate.year < today().year for item in self.todos):
            raise ValueError("规划年份早于当前年份")
        return self


def parse_plan(raw: str, *, generating: bool = False) -> Plan:
    start, end = raw.find("{"), raw.rfind("}")
    if start < 0 or end <= start:
        raise ValueError("规划须为 JSON 对象")
    plan = Plan.model_validate_json(raw[start:end + 1])
    return plan.validate_current_year() if generating else plan


def preview(plan: Plan) -> str:
    def cell(value):
        return str(value or "").replace("\\", "\\\\").replace("|", "\\|").replace("\r\n", "<br>").replace("\n", "<br>").replace("\r", "<br>")

    lines = ["**已生成规划草稿**", f"\n- **目标**：{plan.goal or '未命名规划'}",
             f"- **待办数**：{len(plan.todos)} 个"]
    if plan.startDate and plan.endDate:
        lines.append(f"- **周期**：{plan.startDate} ~ {plan.endDate}")
    if plan.analysis:
        lines.append(f"- **规划说明**：{plan.analysis}")
    lines += ["\n| 待办 | 日期 | 执行星期 | 每日内容 |", "| --- | --- | --- | --- |"]
    for item in plan.todos:
        dates = str(item.startDate) if item.startDate == item.endDate else f"{item.startDate} ~ {item.endDate}"
        weekdays = "、".join("周" + "一二三四五六日"[day - 1] for day in sorted(set(item.weekDays)))
        lines.append(f"| {cell(item.title)} | {dates} | {weekdays} | {cell(item.dayContent or item.title)} |")
    return "\n".join(lines)
