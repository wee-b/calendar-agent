"""执行节点工具白名单与参数校验。拒绝模型传入任何身份字段。"""

from datetime import date as Date
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field

from app.schemas.plan import TodoCreate, WeekDay
from app.schemas.chat.tools import DayDetailResult
from app.schemas.plan.planning_tools import TodoListResult


class Arguments(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class TodoIdentity(Arguments):
    todoId: int = Field(gt=0)


class TodoDate(TodoIdentity):
    date: Date


class AddTodoDay(TodoDate):
    dayContent: str | None = None


class UpdateTodo(TodoCreate):
    todoId: int = Field(gt=0)
    weekDays: list[WeekDay] = Field(min_length=1, max_length=7)


class DayDetail(Arguments):
    date: Date


class MonthCount(Arguments):
    year: int = Field(ge=1, le=9999)
    month: int = Field(ge=1, le=12)


class DailyNote(Arguments):
    date: Date
    content: str


ARGUMENTS = {
    "createTodo": TodoCreate, "deleteTodo": TodoIdentity, "updateTodo": UpdateTodo,
    "toggleTodoDate": TodoDate, "saveDailyNote": DailyNote,
    "removeTodoDay": TodoDate, "addTodoDay": AddTodoDay,
    "queryTodoList": Arguments, "queryDayDetail": DayDetail, "queryMonthCount": MonthCount,
}
READ_TOOLS = frozenset({"queryTodoList", "queryDayDetail", "queryMonthCount"})


class CreatedTodo(BaseModel):
    todoId: Annotated[int, Field(strict=True, gt=0)]
    title: str


class BatchCreateResult(BaseModel):
    createdCount: Annotated[int, Field(strict=True, gt=0)]
    createdTodos: list[CreatedTodo] = Field(min_length=1)


class MonthCountItem(BaseModel):
    date: Date
    count: int = Field(strict=True, ge=0)


class MonthCountResult(BaseModel):
    year: int = Field(strict=True, ge=1, le=9999)
    month: int = Field(strict=True, ge=1, le=12)
    days: list[MonthCountItem]


def validate_read_result(name: str, arguments: dict, result: object) -> None:
    if name == "queryDayDetail":
        if DayDetailResult.model_validate(result).date != arguments["date"]:
            raise ValueError("日程查询返回日期不一致")
    elif name == "queryTodoList":
        TodoListResult.model_validate(result)
    elif name == "queryMonthCount":
        data = MonthCountResult.model_validate(result)
        if (data.year, data.month) != (arguments["year"], arguments["month"]):
            raise ValueError("月历查询返回年月不一致")
        if any((item.date.year, item.date.month) != (data.year, data.month) for item in data.days):
            raise ValueError("月历查询包含范围外日期")
        if len({item.date for item in data.days}) != len(data.days):
            raise ValueError("月历查询包含重复日期")


def validate_write_result(name: str, arguments: dict, result: object) -> None:
    """成功包裹不代表业务已成功；校验 Java 返回的操作与目标。"""
    if not isinstance(result, dict):
        raise ValueError("写工具缺少结构化结果")
    if name == "batchCreateTodos":
        batch = BatchCreateResult.model_validate(result)
        ids = [todo.todoId for todo in batch.createdTodos]
        if batch.createdCount != len(arguments["todos"]) or len(ids) != batch.createdCount or len(set(ids)) != len(ids):
            raise ValueError("批量创建结果数量或 ID 不一致")
        return
    if name == "saveDailyNote":
        if result.get("saved") is not True or result.get("date") != arguments["date"]:
            raise ValueError("日记保存结果不匹配")
        return
    operations = {"createTodo": "created", "deleteTodo": "deleted", "updateTodo": "updated",
                  "toggleTodoDate": "toggled", "removeTodoDay": "removed", "addTodoDay": "added"}
    todo_id = result.get("todoId")
    if (result.get("operation") != operations[name] or type(todo_id) is not int or todo_id <= 0
            or ("todoId" in arguments and todo_id != arguments["todoId"])):
        raise ValueError("待办写入结果不匹配")
    if "date" in arguments and result.get("date") != arguments["date"]:
        raise ValueError("待办日期结果不匹配")
    if name == "toggleTodoDate" and (type(result.get("status")) is not int or result["status"] not in (0, 1)):
        raise ValueError("待办状态结果无效")
