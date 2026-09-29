"""只读日期详情工具的参数、返回值和模型侧声明。"""

from pydantic import BaseModel, ConfigDict, Field


class DayDetailArguments(BaseModel):
    """模型生成的参数只能包含 date；具体日期是否存在由调用方继续校验。"""

    model_config = ConfigDict(strict=True, extra="forbid")

    date: str = Field(pattern=r"^\d{4}-\d{2}-\d{2}$")


class DayTodoItem(BaseModel):
    """Java 日期详情中单条待办的对外字段。"""

    todoId: int
    title: str
    color: str | None = None
    dayContent: str | None = None  # 该待办在查询日期的具体安排。
    status: int | None = None  # 该日期的完成状态，由 Java 定义取值。


class DayDetailResult(BaseModel):
    """Java queryDayDetail 的结果，校验后再作为 tool 消息交回模型。"""

    date: str
    todos: list[DayTodoItem]
    dailyNote: str | None = None  # 当天日记可以为空。

DAY_DETAIL_TOOL_NAME = "queryDayDetail"
MAX_MODEL_ROUNDS = 3

# 此结构直接发送给 OpenAI-compatible 接口，字段名需与模型工具协议一致。
# 参数约束同时由 DayDetailArguments 在模型返回时执行，不能只依赖模型遵守声明。
DAY_DETAIL_TOOL = {
    "type": "function",
    "function": {
        "name": DAY_DETAIL_TOOL_NAME,
        "description": "查询指定日期的日程、待办和日记。日期格式为 YYYY-MM-DD。",
        "parameters": {
            "type": "object",
            "properties": {"date": {"type": "string", "format": "date"}},
            "required": ["date"],
            "additionalProperties": False,
        },
    },
}
