"""资料前置、LLM澄清、跨轮恢复及区间变化的行为测试。"""

import json
import unittest
from datetime import timedelta
from time import time
from types import SimpleNamespace
from unittest.mock import AsyncMock

from app.all_graph.conversation_graph import ConversationGraph
from app.all_graph.nodes.execute_node import ExecuteNode
from app.all_graph.nodes.image_node import ImageNode
from app.all_graph.nodes.plan_node import PlanNode, SCHEDULE_SNAPSHOT_TTL_SECONDS
from app.all_graph.nodes.route_node import RouteAgent, RouteDecision
from app.all_graph.nodes.summary_node import SummaryNode
from app.all_graph.plan_graph import PlanningGraph
from app.core.exception.error_code import ErrorCode
from app.core.exception.exceptions import BusinessException, McpClientError
from app.repository.flow_state import FlowStateRepository
from app.schemas.chat.model_stream import AssistantMessage
from app.schemas.plan import today
from app.schemas.plan.planning_tools import PlanClarification, PlanningRange, ScheduleRangeResult
from app.schemas.plan.preparation import PlanningPreparation, infer_planning_range
from app.schemas.statemachine.flow import AgentTurnResult, ConversationStage, PendingTask, UserSignal
from app.schemas.statemachine.transitions import AgentType
from app.service.chat import ChatService
from tests.conversation_fakes import MemoryFlowRepository
from tests.planning_fakes import day_range
from tests.test_planning_graph import draft, planning_service, review, turn_context


def clarify(*questions):
    return AssistantMessage(content=json.dumps({"action": "clarify", "questions": list(questions)}, ensure_ascii=False))


class RangeMcp:
    def __init__(self, trace=None, failure=None):
        self.calls, self.trace, self.failure = [], trace, failure

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        pass

    async def call_tool(self, name, arguments, token):
        if name != "queryScheduleRange":
            raise AssertionError("不允许查询全部待办或逐日查询")
        self.calls.append((name, arguments, token))
        if self.trace is not None:
            self.trace.append("schedule")
        if self.failure:
            raise self.failure
        return {**arguments, "schedules": [{"todoId": 9, "title": "已有安排",
                    "date": arguments["startDate"], "dayContent": "19:00-20:00 工作", "status": 0}]}


def state(pending=None, signal=UserSignal.NEW_PLAN, message="安排英语学习", date_range=None, **kwargs):
    return {"user_id": 7, "session_id": "s", "message": message, "task": message,
            "pending": pending or PendingTask(), "signal": signal, "planning_range": date_range, **kwargs}


def restore(pending):
    return FlowStateRepository.pending(SimpleNamespace(
        pending_task=pending.task, pending_draft_id=pending.draft_id,
        pending_payload=FlowStateRepository.serialize_payload(pending), image_instruction=pending.image_instruction))


class PlanningClarificationTests(unittest.IsolatedAsyncioTestCase):
    async def test_all_sources_precede_first_llm_and_survive_restart_until_user_answers(self):
        trace = []

        async def memories(*_):
            trace.append("preferences")
            return [{"memory_type": "PREFERENCE_TIME", "content": "晚上学习"}]

        async def search(*_):
            trace.append("rag")
            return [SimpleNamespace(payload=SimpleNamespace(source="文章", section="方法", text="每天背单词"))]

        async def first_chat(messages, tools=None):
            trace.append("llm")
            material = json.loads(messages[1]["content"])
            self.assertIn("晚上学习", str(material["preferences"]))
            self.assertIn("每天背单词", material["references"])
            self.assertEqual("19:00-20:00 工作", material["schedules"]["schedules"][0]["dayContent"])
            return clarify("主要目标是词汇还是听力？", "每天能投入多久？")

        service, mcp = planning_service(), RangeMcp(trace)
        service.memories = AsyncMock(side_effect=memories)
        documents = SimpleNamespace(search=AsyncMock(side_effect=search))
        first_model = SimpleNamespace(chat=AsyncMock(side_effect=first_chat))
        first = await PlanNode(service, first_model, documents, lambda: mcp)(
            state(date_range=day_range(), document_ids=[42]), turn_context())
        self.assertEqual(["preferences", "rag", "schedule", "llm"], trace)
        self.assertEqual("PLAN_CLARIFICATION", first.dispatch_type)
        self.assertEqual(2, len(first.pending.planning.questions))
        service.save_draft.assert_not_awaited()
        self.assertIsNone(first.pending.draft_id)

        # 用仓储序列化后创建全新节点，证明不依赖进程内缓存或 LangGraph checkpointer。
        pending = restore(first.pending)
        next_model = SimpleNamespace(chat=AsyncMock(side_effect=[draft("听力复习"), review()]))
        second = await PlanNode(service, next_model, documents, lambda: mcp)(
            state(pending, UserSignal.MODIFY, "听力，每天两小时"), turn_context())
        self.assertEqual(19, second.pending.draft_id)
        self.assertEqual([], second.pending.planning.questions)
        service.memories.assert_awaited_once()
        documents.search.assert_awaited_once()
        self.assertEqual(1, len(mcp.calls))
        service.save_draft.assert_awaited_once()
        prompt = next_model.chat.call_args_list[0].args[0][1]["content"]
        for text in ("安排英语学习", "每天能投入多久", "听力，每天两小时", "每天背单词"):
            self.assertIn(text, prompt)
        self.assertEqual(2, next_model.chat.await_count)

    async def test_unknown_dates_ask_before_any_schedule_query_and_answer_triggers_prefetch(self):
        service, mcp = planning_service(), RangeMcp()
        model = SimpleNamespace(chat=AsyncMock(side_effect=[clarify("希望从哪天开始、到哪天结束？"), draft(), review()]))
        node = PlanNode(service, model, mcp_factory=lambda: mcp)
        first = await node(state(), turn_context())
        self.assertEqual([], mcp.calls)
        self.assertIsNone(first.pending.planning.date_range)
        first_tools = model.chat.call_args_list[0].kwargs["tools"]
        self.assertNotIn("queryScheduleRange", [tool["function"]["name"] for tool in first_tools])
        second = await node(state(restore(first.pending), UserSignal.MODIFY,
            f"就安排在{today()}这一天，每天两小时"), turn_context())
        self.assertEqual(1, len(mcp.calls))
        self.assertEqual(day_range(), second.pending.planning.date_range)
        self.assertIsNotNone(json.loads(model.chat.call_args_list[1].args[0][1]["content"])["schedules"])

    async def test_changed_dates_refresh_only_schedule_snapshot_before_second_model(self):
        tomorrow = today() + timedelta(days=1)
        service, mcp = planning_service(), RangeMcp()
        model = SimpleNamespace(chat=AsyncMock(side_effect=[clarify("每天可投入多久？"), draft(day=tomorrow), review()]))
        node = PlanNode(service, model, mcp_factory=lambda: mcp)
        first = await node(state(date_range=day_range()), turn_context())
        second = await node(state(restore(first.pending), UserSignal.MODIFY, "改为明天，每天两小时",
            PlanningRange(startDate=tomorrow, endDate=tomorrow)), turn_context())
        self.assertEqual([str(today()), str(tomorrow)], [call[1]["startDate"] for call in mcp.calls])
        service.memories.assert_awaited_once()
        self.assertEqual(tomorrow, second.pending.planning.schedules.startDate)
        # 原先已持久化的问题和资料没有被失败/重试过程原地修改。
        self.assertEqual(today(), first.pending.planning.schedules.startDate)
        self.assertTrue(first.pending.planning.questions)

    async def test_expired_schedule_is_refreshed_after_a_long_user_pause(self):
        service, mcp = planning_service(), RangeMcp()
        model = SimpleNamespace(chat=AsyncMock(side_effect=[clarify("每天可投入多久？"), draft(), review()]))
        node = PlanNode(service, model, mcp_factory=lambda: mcp)
        first = await node(state(date_range=day_range()), turn_context())
        first.pending.planning.schedules_at = time() - SCHEDULE_SNAPSHOT_TTL_SECONDS - 1
        await node(state(restore(first.pending), UserSignal.MODIFY, "每天两小时"), turn_context())
        self.assertEqual(2, len(mcp.calls))

    async def test_changed_but_unknown_period_invalidates_old_dates_and_asks_again(self):
        service, mcp = planning_service(), RangeMcp()
        model = SimpleNamespace(chat=AsyncMock(side_effect=[
            clarify("每天可以投入多久？"), clarify("下个月具体从哪天开始、到哪天结束？")]))
        node = PlanNode(service, model, mcp_factory=lambda: mcp)
        first = await node(state(date_range=day_range()), turn_context())
        second = await node(state(restore(first.pending), UserSignal.MODIFY,
                                  "改为下个月，具体日期待定"), turn_context())
        self.assertIsNone(second.pending.planning.date_range)
        self.assertIsNone(second.pending.planning.schedules)
        self.assertEqual(1, len(mcp.calls))
        self.assertIsNone(json.loads(model.chat.call_args_list[1].args[0][1]["content"])["planningRange"])
        service.save_draft.assert_not_awaited()

    async def test_preparation_failure_stops_before_model_and_preserves_previous_pending(self):
        for code in ("AUTH_FAILED", "INVALID_RESPONSE", "JAVA_UNAVAILABLE"):
            with self.subTest(code=code):
                service = planning_service()
                model = SimpleNamespace(chat=AsyncMock())
                previous = PendingTask(task="原计划", draft_id=8, plan_preview="原草稿")
                mcp = RangeMcp(failure=McpClientError(code, "private backend data"))
                with self.assertRaises(BusinessException) as caught:
                    await PlanNode(service, model, mcp_factory=lambda: mcp)(state(previous, date_range=day_range()), turn_context())
                expected = ErrorCode.MCP_AUTH_FAILED if code == "AUTH_FAILED" else ErrorCode.PLAN_CONTEXT_UNAVAILABLE
                self.assertEqual(expected.code, caught.exception.code)
                model.chat.assert_not_awaited()
                service.save_draft.assert_not_awaited()
                self.assertEqual("原草稿", previous.plan_preview)

    async def test_model_cannot_save_invented_dates_when_user_has_not_confirmed_range(self):
        model = SimpleNamespace(chat=AsyncMock(side_effect=[draft(), clarify("希望从哪天开始、到哪天结束？")]))
        result = await PlanningGraph(model).run([{"role": "user", "content": "安排学习"}],
            user_id=7, requirement="安排学习", context=turn_context())
        self.assertIsInstance(result, PlanClarification)
        self.assertEqual(2, model.chat.await_count)
        self.assertIn("日期尚未确认", model.chat.call_args_list[1].args[0][-1]["content"])

    async def test_questions_are_one_to_three_nonblank_distinct_strings(self):
        for questions in ([], [""], ["重复？", "重复？"], ["一？", "二？", "三？", "四？"]):
            with self.subTest(questions=questions):
                model = SimpleNamespace(chat=AsyncMock(side_effect=[clarify(*questions), clarify("具体目标是什么？")]))
                result = await PlanningGraph(model).run([{"role": "user", "content": "学习"}],
                    user_id=7, requirement="学习", context=turn_context(), date_range=day_range())
                self.assertEqual(["具体目标是什么？"], result.questions)
                self.assertEqual(2, model.chat.await_count)

    async def test_waiting_answers_block_sync_and_image_even_when_old_draft_exists(self):
        pending = PendingTask(task="修改原计划", draft_id=8, plan_preview="原草稿",
                              planning=PlanningPreparation(questions=["每天能投入多久？"]))
        current = state(pending, UserSignal.SYNC_PLAN)
        drafts = SimpleNamespace(find_pending=AsyncMock())
        sync = await ExecuteNode(drafts=drafts)._sync_plan(current, turn_context())
        image = await ImageNode(client=SimpleNamespace(generate=AsyncMock()), drafts=drafts,
                                storage=SimpleNamespace())(current, turn_context())
        self.assertFalse(sync.completed)
        self.assertFalse(image.completed)
        drafts.find_pending.assert_not_awaited()

    async def test_pending_clarification_survives_temporary_query_and_finishes_on_answer(self):
        repo, service, mcp = MemoryFlowRepository(), planning_service(), RangeMcp()
        route = SimpleNamespace(route=AsyncMock(side_effect=[
            RouteDecision(signal=UserSignal.NEW_PLAN, planning_range=day_range()),
            RouteDecision(signal=UserSignal.NEW_QUERY),
            RouteDecision(signal=UserSignal.MODIFY),
        ]))
        model = SimpleNamespace(chat=AsyncMock(side_effect=[clarify("每天可投入多久？"), draft(), review()]))
        chat = ChatService(repo)
        graph = ConversationGraph(user_id=7, session_id="s", chat_service=chat,
            summary_node=SummaryNode(service=SimpleNamespace(snapshot=AsyncMock(return_value=None))), route_agent=route)
        graph.handlers[AgentType.PLANNER] = PlanNode(service, model, mcp_factory=lambda: mcp)

        async def query(state, context):
            return AgentTurnResult(reply="日程查询完成。", completed=True, pending=state["pending"], dispatch_type="QUERY")

        graph.handlers[AgentType.CHAT] = query
        first = await graph.run_turn("安排学习", "user-token")
        self.assertIsNone(first.pending.draft_id)
        self.assertEqual(1, model.chat.await_count)
        second = await graph.run_turn("另外查一下今天的安排", "user-token")
        self.assertEqual(first.pending.planning, second.pending.planning)
        final = await graph.run_turn("每天两小时", "user-token")
        self.assertEqual(19, final.pending.draft_id)
        self.assertEqual([], final.pending.planning.questions)
        self.assertEqual(3, len(repo.saved))
        self.assertEqual(1, len(mcp.calls))
        self.assertIn("每天两小时", service.save_draft.call_args.args[2])

    async def test_route_receives_questions_and_parses_changed_date_range(self):
        tomorrow = today() + timedelta(days=1)
        raw = json.dumps({"userSignal": "MODIFY", "planningRange": {
            "startDate": str(tomorrow), "endDate": str(tomorrow)}})
        model = SimpleNamespace(complete=AsyncMock(return_value=raw))
        context = SimpleNamespace(previousReply=None, userMessages=[SimpleNamespace(content="改到明天")])
        service = SimpleNamespace(get_route_context=AsyncMock(return_value=context))
        await RouteAgent(model, service).route(7, "s", "改到明天", ConversationStage.PLAN,
            PendingTask(task="学习", planning=PlanningPreparation(date_range=day_range(), questions=["哪天安排？"])))
        self.assertIn("哪天安排", model.complete.call_args.args[0][0]["content"])
        self.assertEqual(tomorrow, RouteAgent.parse(raw).planning_range.startDate)

    async def test_cancelling_waiting_plan_clears_questions_and_cached_sources(self):
        repo, service, mcp = MemoryFlowRepository(), planning_service(), RangeMcp()
        model = SimpleNamespace(chat=AsyncMock(return_value=clarify("每天可投入多久？")))
        route = SimpleNamespace(route=AsyncMock(side_effect=[
            RouteDecision(signal=UserSignal.NEW_PLAN, planning_range=day_range()),
            RouteDecision(signal=UserSignal.REJECT)]))
        graph = ConversationGraph(user_id=7, session_id="s", chat_service=ChatService(repo), route_agent=route,
            summary_node=SummaryNode(service=SimpleNamespace(snapshot=AsyncMock(return_value=None))))
        graph.handlers[AgentType.PLANNER] = PlanNode(service, model, mcp_factory=lambda: mcp)
        await graph.run_turn("安排学习", "user-token")
        result = await graph.run_turn("取消这个规划", "user-token")
        self.assertEqual(ConversationStage.CHAT, result.stage)
        self.assertIsNone(result.pending.planning)
        self.assertIsNone(repo.states[7, "s"].pending_payload)
        self.assertEqual(1, len(mcp.calls))
        model.chat.assert_awaited_once()
        service.save_draft.assert_not_awaited()

    async def test_explicit_new_task_does_not_reuse_previous_clarification_sources(self):
        service, mcp = planning_service(), RangeMcp()
        documents = SimpleNamespace(search=AsyncMock(return_value=[SimpleNamespace(payload=SimpleNamespace(
            source="英语文章", section="正文", text="每天背单词"))]))
        model = SimpleNamespace(chat=AsyncMock(side_effect=[clarify("词汇还是听力？"), draft("工作任务"), review()]))
        node = PlanNode(service, model, documents, lambda: mcp)
        first = await node(state(date_range=day_range(), document_ids=[42]), turn_context())
        second = await node(state(restore(first.pending), UserSignal.NEW_PLAN,
                                  "重新制定工作计划", day_range()), turn_context())
        self.assertEqual([], second.pending.planning.document_ids)
        self.assertIsNone(json.loads(model.chat.call_args_list[1].args[0][1]["content"])["references"])
        documents.search.assert_awaited_once()
        self.assertEqual(2, service.memories.await_count)
        self.assertEqual(2, len(mcp.calls))


class PlanningStateTests(unittest.TestCase):
    def test_payload_roundtrip_preserves_old_preview_and_new_context(self):
        pending = PendingTask(task="学习", draft_id=8, plan_preview="旧的 Markdown 草稿",
            planning=PlanningPreparation(date_range=day_range(), document_ids=[42], references="文章片段",
                questions=["每天能学多久？"], schedules=ScheduleRangeResult(**day_range().model_dump(), schedules=[])))
        self.assertEqual(pending, restore(pending))
        legacy = PendingTask(task="学习", draft_id=8, plan_preview="旧的 Markdown 草稿")
        self.assertEqual("旧的 Markdown 草稿", FlowStateRepository.serialize_payload(legacy))
        self.assertEqual(legacy, restore(legacy))
        self.assertNotIn("token", FlowStateRepository.serialize_payload(pending))

    def test_corrupted_known_payload_is_rejected_instead_of_losing_questions(self):
        with self.assertRaises(BusinessException) as caught:
            FlowStateRepository.pending(SimpleNamespace(pending_task="学习", pending_draft_id=8,
                pending_payload='{"_format":"planning-v1","planning":{"questions":[" "]}}', image_instruction=None))
        self.assertEqual(ErrorCode.PLAN_STATE_INVALID.code, caught.exception.code)

    def test_date_fallback_does_not_invent_start_for_unanchored_duration(self):
        self.assertIsNone(infer_planning_range("做一个两周的学习计划"))
        self.assertIsNone(infer_planning_range("学习30天"))
        self.assertIsNone(infer_planning_range("下周一的学习计划"))
        self.assertIsNone(infer_planning_range("从明天开始学习"))
        self.assertIsNone(infer_planning_range("从后天到今天学习"))
        self.assertEqual(day_range(), infer_planning_range("安排今天的学习"))
        period = infer_planning_range("安排未来3天的学习")
        self.assertEqual(today() + timedelta(days=2), period.endDate)
