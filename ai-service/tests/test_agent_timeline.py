import unittest
from types import SimpleNamespace
from time import perf_counter

from app.all_graph.conversation_graph import TurnContext
from app.schemas.chat.model_stream import (
    AgentStatusData, AgentStatusEvent, AgentStepEvent, ToolCallData,
    ToolCallLifecycleEvent, ToolResultData, ToolResultEvent,
)
from app.schemas.chat.timeline import summarize_tool_result


class AgentTimelineTests(unittest.IsolatedAsyncioTestCase):
    async def test_stream_updates_one_tool_row_and_keeps_only_safe_labels(self):
        events = []
        async def collect(event):
            events.append(event)

        context = TurnContext(token="secret-token", flow_state=SimpleNamespace(),
                              emit=collect, started=perf_counter())
        await context.send(AgentStatusEvent(data=AgentStatusData(agent="route", round=1, stage="model")))
        await context.set_agent_narration("识别意图", "我会先核对明天的日程。")
        await context.send(AgentStatusEvent(data=AgentStatusData(agent="chat", round=1, stage="model")))
        await context.send(ToolCallLifecycleEvent(event="tool_call_start", data=ToolCallData(
            round=1, call_id="private-call", tool="queryDayDetail")))
        await context.send(ToolResultEvent(data=ToolResultData(
            call_id="private-call", status="success", summary="2026-10-04：2 项待办，有日记。")))
        await context.send(AgentStatusEvent(data=AgentStatusData(agent="chat", round=2, stage="model")))
        await context.finish_steps()

        self.assertEqual(["识别意图", "生成回复", "查询日程", "生成回复"],
                         [step.label for step in context.agent_steps])
        self.assertTrue(all(step.status == "success" for step in context.agent_steps))
        published = [event for event in events if isinstance(event, AgentStepEvent)]
        self.assertEqual((1, 1), (published[0].data.id, published[1].data.id))
        self.assertEqual("我会先核对明天的日程。", published[1].data.narration)
        self.assertEqual("2026-10-04：2 项待办，有日记。", context.agent_steps[2].resultSummary)
        self.assertEqual(context.agent_steps[-1].id, published[-1].data.id)
        self.assertNotIn("secret-token", str([step.model_dump() for step in context.agent_steps]))
        self.assertNotIn("private-call", str([step.model_dump() for step in context.agent_steps]))

    async def test_same_tool_called_twice_has_two_rows(self):
        context = TurnContext(token="t", flow_state=SimpleNamespace(), started=perf_counter())
        for call_id in ("first", "second"):
            await context.send(ToolCallLifecycleEvent(event="tool_call_start", data=ToolCallData(
                round=1, call_id=call_id, tool="queryDayDetail")))
        self.assertEqual([1, 2], [step.id for step in context.agent_steps])

    def test_tool_result_summary_uses_only_selected_fields(self):
        summary = summarize_tool_result("queryDayDetail", {
            "date": "2026-10-04", "todos": [{"title": "private title"}],
            "dailyNote": "private note", "token": "secret-token",
        })
        self.assertEqual("2026-10-04：1 项待办，有日记。", summary)
        self.assertNotIn("private", summary)
