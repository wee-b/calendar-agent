"""规划只读工具及校验协议；引用文章在模型调用前检索，不属于工具。"""

from datetime import date
import re
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, field_validator, model_validator


class QueryTodoListArguments(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid")


class QueryPreferencesArguments(QueryTodoListArguments):
    pass


class PlanningRange(BaseModel):
    model_config = ConfigDict(extra="forbid")

    startDate: date
    endDate: date

    @field_validator("startDate", "endDate", mode="before")
    @classmethod
    def canonical_date(cls, value):
        if isinstance(value, date):
            return value
        if not isinstance(value, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
            raise ValueError("日期必须使用 YYYY-MM-DD 格式")
        return value

    @model_validator(mode="after")
    def valid_range(self):
        if not 0 <= (self.endDate - self.startDate).days <= 180:
            raise ValueError("规划区间须为0到180天")
        return self


class ScheduleRangeArguments(PlanningRange):
    pass


class ScheduleItem(BaseModel):
    todoId: int = Field(strict=True, gt=0)
    title: str = Field(min_length=1)
    date: date
    dayContent: str | None = None
    status: int | None = Field(default=None, strict=True)


class ScheduleRangeResult(PlanningRange):
    # Java 结果可增加其他元数据，只有这些字段进入规划上下文。
    model_config = ConfigDict(extra="ignore")
    schedules: list[ScheduleItem]

    @model_validator(mode="after")
    def dates_within_range(self):
        if any(not self.startDate <= item.date <= self.endDate for item in self.schedules):
            raise ValueError("区间查询返回范围外日程")
        keys = [(item.todoId, item.date) for item in self.schedules]
        if len(keys) != len(set(keys)):
            raise ValueError("区间查询返回重复日程")
        return self


Question = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=240)]


class PlanClarification(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid")
    action: Literal["clarify"]
    questions: list[Question] = Field(min_length=1, max_length=3)

    @model_validator(mode="after")
    def distinct_questions(self):
        if len(set(self.questions)) != len(self.questions):
            raise ValueError("澄清问题不能重复")
        return self


class ExistingTodo(BaseModel):
    """仅接收规划需要的字段，Java 其他字段不会进入模型上下文。"""

    todoId: int = Field(strict=True, gt=0)
    title: str = Field(min_length=1)
    startDate: date
    endDate: date
    weekDays: list[int] | None = None
    dates: list[date]
    status: int | None = Field(default=None, strict=True)

    @model_validator(mode="after")
    def valid_range(self):
        if self.endDate < self.startDate:
            raise ValueError("已有待办的日期范围无效")
        if self.weekDays and any(day not in range(1, 8) for day in self.weekDays):
            raise ValueError("已有待办的执行星期无效")
        # dates 可能包含 addTodoDay 添加的范围外日期，应保留真实安排。
        return self


class TodoListResult(BaseModel):
    todos: list[ExistingTodo]

    @model_validator(mode="after")
    def unique_ids(self):
        if len({todo.todoId for todo in self.todos}) != len(self.todos):
            raise ValueError("批量查询返回重复待办 ID")
        return self


class Preference(BaseModel):
    memory_type: str
    content: str = Field(min_length=1)


class PreferencesResult(BaseModel):
    preferences: list[Preference]


QUERY_TODO_LIST_TOOL_NAME = "queryTodoList"
QUERY_SCHEDULE_RANGE_TOOL_NAME = "queryScheduleRange"
QUERY_PREFERENCES_TOOL_NAME = "queryPreferences"


def _tool(name: str, description: str, arguments: type[BaseModel]) -> dict:
    return {"type": "function", "function": {
        "name": name, "description": description,
        "parameters": arguments.model_json_schema(),
    }}


PLANNING_TOOLS = [
    _tool(QUERY_SCHEDULE_RANGE_TOOL_NAME,
          "获取本轮已准备的规划区间日程，参数必须与上下文中已确认的日期区间一致。"
          "复用前置批量查询结果，包含每日内容；日期尚未确认时应向用户询问，不能擅自扩大区间。",
          ScheduleRangeArguments),
    _tool(QUERY_PREFERENCES_TOOL_NAME,
          "查询与本轮规划相关的时间、负荷、风格偏好、避忌和长期目标。本轮用户要求优先于历史偏好。",
          QueryPreferencesArguments),
]

PLANNING_ARGUMENTS = {
    QUERY_SCHEDULE_RANGE_TOOL_NAME: ScheduleRangeArguments,
    QUERY_PREFERENCES_TOOL_NAME: QueryPreferencesArguments,
}


class PlanIssue(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid")

    category: Literal["constraint", "knowledge", "schedule"]
    message: str = Field(min_length=1, max_length=500)

    @field_validator("message")
    @classmethod
    def nonblank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("校验问题不能为空")
        return value.strip()


class PlanReview(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid")

    issues: list[PlanIssue] = Field(max_length=10)
