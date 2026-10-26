"""保留已有节点测试的生成断言，为新增的独立校验调用提供固定结果。"""

from types import SimpleNamespace
from unittest.mock import AsyncMock

from app.schemas.chat.model_stream import AssistantMessage
from app.schemas.plan import today
from app.schemas.plan.planning_tools import PlanningRange


def day_range():
    return PlanningRange(startDate=today(), endDate=today())


class EmptyScheduleMcp:
    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        pass

    async def call_tool(self, name, arguments, token):
        if name != "queryScheduleRange":
            raise AssertionError("规划只使用区间查询")
        return {**arguments, "schedules": []}


def text_planner(complete):
    async def chat(messages, tools=None):
        if messages[0]["content"].startswith("你是规划约束校验器"):
            return AssistantMessage(content='{"issues":[]}')
        return AssistantMessage(content=await complete(messages))

    return SimpleNamespace(complete=complete, chat=AsyncMock(side_effect=chat))
