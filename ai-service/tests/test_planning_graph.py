"""规划图的查询边界、证据传递、修正预算和 SSE/草稿隔离回归。"""

import asyncio
import json
import unittest
from datetime import timedelta
from time import perf_counter
from types import SimpleNamespace
from unittest.mock import AsyncMock

from app.all_graph.conversation_graph import ConversationGraph, TurnContext
from app.all_graph.nodes.plan_node import PlanNode
from app.all_graph.nodes.route_node import RouteDecision
from app.all_graph.nodes.summary_node import SummaryNode
from app.all_graph.plan_graph import MAX_PLAN_MODEL_ROUNDS, PlanningGraph
from app.api.conversation_transport import ConversationTransport
from app.core.exception.error_code import ErrorCode
from app.core.exception.exceptions import BusinessException, McpClientError
from app.schemas.chat import ChatRequest
from app.schemas.chat.model_stream import (
    AgentStepEvent, AssistantDeltaEvent, AssistantMessage, DoneEvent, ResultEvent,
    ToolCall, ToolFunction,
)
from app.schemas.plan import today
from app.schemas.statemachine.flow import PendingTask, UserSignal
from app.schemas.statemachine.transitions import AgentType
from app.service.chat import ChatService
from tests.conversation_fakes import MemoryFlowRepository
from tests.planning_fakes import day_range, EmptyScheduleMcp


def draft(title="英语复习", day=None):
    day = day or today()
    return AssistantMessage(content=json.dumps({
        "goal": "复习", "analysis": "根据要求安排任务。",
        "startDate": str(day), "endDate": str(day),
        "todos": [{"title": title, "startDate": str(day), "endDate": str(day),
                   "weekDays": [day.isoweekday()]}],
    }, ensure_ascii=False))


def review(*issues):
    return AssistantMessage(content=json.dumps({"issues": list(issues)}, ensure_ascii=False))


def tool_calls(*names, prefix="call", arguments="{}"):
    return AssistantMessage(tool_calls=[ToolCall(id=f"{prefix}-{index}", function=ToolFunction(
        name=name, arguments=day_range().model_dump_json() if name == "queryScheduleRange" and arguments == "{}"
        else arguments)) for index, name in enumerate(names)])


def turn_context(**kwargs):
    return TurnContext(token="secret-token", flow_state=SimpleNamespace(), started=perf_counter(), **kwargs)


def planning_service(memories=None):
    return SimpleNamespace(memories=AsyncMock(return_value=memories or []),
                           save_draft=AsyncMock(return_value=SimpleNamespace(draft_id=19)))


class Mcp:
    def __init__(self, result=None, failure=None):
        self.result = result if result is not None else {**day_range().model_dump(mode="json"), "schedules": []}
        self.failure = failure
        self.calls = []
        self.closed = False

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        self.closed = True

    async def call_tool(self, name, arguments, token):
        self.calls.append((name, arguments, token))
        if self.failure:
            raise self.failure
        return self.result


class PlanningGraphTests(unittest.IsolatedAsyncioTestCase):
    async def run_graph(self, model, *, context=None, service=None, mcp=None, **kwargs):
        graph = PlanningGraph(model, service or planning_service(), lambda: mcp or Mcp())
        kwargs.setdefault("date_range", day_range())
        return await graph.run([{"role": "user", "content": "安排英语复习"}],
                               user_id=7, requirement="安排英语复习",
                               context=context or turn_context(), **kwargs)

    async def test_first_draft_passes_structural_and_semantic_validation(self):
        model = SimpleNamespace(chat=AsyncMock(side_effect=[draft(), review()]))
        ctx = turn_context()
        plan = await self.run_graph(model, context=ctx)
        self.assertEqual("英语复习", plan.todos[0].title)
        self.assertEqual(2, ctx.rounds)
        tools = model.chat.call_args_list[0].kwargs["tools"]
        self.assertEqual({"queryScheduleRange", "queryPreferences"}, {t["function"]["name"] for t in tools})
        self.assertIsNone(model.chat.call_args_list[1].kwargs["tools"])
        self.assertEqual(["制定规划", "校验规划约束"], [step.label for step in ctx.agent_steps])

    async def test_batch_schedule_and_preferences_are_cached_and_sent_to_reviewer(self):
        memories = [{"memory_type": "PREFERENCE_TIME", "content": "偏好晚上学习", "confidence": 0.9},
                    {"memory_type": "BEHAVIOR_PATTERN", "content": "不应进入偏好结果"}]
        service = planning_service(memories)
        day = str(today())
        mcp = Mcp({"startDate": day, "endDate": day, "schedules": [
            {"todoId": 3, "title": "已有任务", "date": day, "dayContent": "19:00-20:00 阅读",
             "privateField": "不要回填"}]})
        model = SimpleNamespace(chat=AsyncMock(side_effect=[
            tool_calls("queryScheduleRange", "queryPreferences"),
            tool_calls("queryScheduleRange", "queryPreferences", prefix="again"),
            draft(), review(),
        ]))
        ctx = turn_context()
        await self.run_graph(model, context=ctx, service=service, mcp=mcp)
        self.assertEqual([("queryScheduleRange", day_range().model_dump(mode="json"), "secret-token")], mcp.calls)
        service.memories.assert_awaited_once_with(7, "安排英语复习")
        messages = model.chat.call_args_list[2].args[0]
        results = [message for message in messages if message["role"] == "tool"]
        self.assertEqual(["call-0", "call-1", "again-0", "again-1"],
                         [message["tool_call_id"] for message in results])
        self.assertNotIn("privateField", str(results))
        self.assertNotIn("BEHAVIOR_PATTERN", str(results))
        evidence = json.loads(model.chat.call_args_list[-1].args[0][1]["content"])["queries"]
        self.assertEqual(day, evidence["queryScheduleRange"]["schedules"][0]["date"])
        self.assertEqual("偏好晚上学习", evidence["queryPreferences"]["preferences"][0]["content"])
        self.assertTrue(any("复用" in (step.resultSummary or "") for step in ctx.agent_steps))
        self.assertNotIn("secret-token", str([step.model_dump() for step in ctx.agent_steps]))
        self.assertTrue(mcp.closed)

    async def test_preloaded_preferences_do_not_query_database_twice(self):
        service = planning_service()
        model = SimpleNamespace(chat=AsyncMock(side_effect=[tool_calls("queryPreferences"), draft(), review()]))
        await self.run_graph(model, service=service,
                             memories=[{"memory_type": "AVOIDANCE", "content": "周末不学习"}])
        service.memories.assert_not_awaited()
        tool_message = model.chat.call_args_list[1].args[0][-1]
        self.assertIn("周末不学习", tool_message["content"])

    async def test_all_tool_arguments_validated_before_any_query(self):
        mcp = Mcp()
        response = tool_calls("queryScheduleRange", "queryPreferences")
        response.tool_calls[1].function.arguments = '{"user_id":999}'
        model = SimpleNamespace(chat=AsyncMock(return_value=response))
        with self.assertRaises(BusinessException) as caught:
            await self.run_graph(model, mcp=mcp)
        self.assertEqual(ErrorCode.MODEL_TOOL_ARGUMENT_INVALID.code, caught.exception.code)
        self.assertEqual([], mcp.calls)

    async def test_unknown_single_day_write_or_rag_tools_are_rejected(self):
        for name in ("queryDayDetail", "createTodo", "searchKnowledge"):
            with self.subTest(name=name):
                mcp = Mcp()
                model = SimpleNamespace(chat=AsyncMock(return_value=tool_calls(name)))
                with self.assertRaises(BusinessException) as caught:
                    await self.run_graph(model, mcp=mcp)
                self.assertEqual(ErrorCode.MODEL_TOOL_UNAPPROVED.code, caught.exception.code)
                self.assertEqual([], mcp.calls)

    async def test_duplicate_tool_ids_and_excessive_parallel_calls_are_rejected(self):
        duplicate = tool_calls("queryScheduleRange", "queryPreferences")
        duplicate.tool_calls[1].id = duplicate.tool_calls[0].id
        for response in (duplicate, tool_calls(*(["queryScheduleRange"] * 5))):
            with self.subTest(response=response):
                mcp = Mcp()
                with self.assertRaises(BusinessException):
                    await self.run_graph(SimpleNamespace(chat=AsyncMock(return_value=response)), mcp=mcp)
                self.assertEqual([], mcp.calls)

    async def test_format_and_past_date_errors_are_repaired_before_semantic_review(self):
        for response in (AssistantMessage(content="not json"), draft(day=today() - timedelta(days=1))):
            with self.subTest(response=response):
                model = SimpleNamespace(chat=AsyncMock(side_effect=[response, draft(), review()]))
                ctx = turn_context()
                await self.run_graph(model, context=ctx)
                self.assertEqual(3, model.chat.await_count)
                self.assertIn("校验反馈", model.chat.call_args_list[1].args[0][-1]["content"])
                self.assertTrue(any(step.label == "反馈修正规划" for step in ctx.agent_steps))

    async def test_semantic_feedback_keeps_requirements_and_reference_evidence(self):
        issues = [{"category": category, "message": f"修正{category}问题"}
                  for category in ("constraint", "knowledge", "schedule")]
        model = SimpleNamespace(chat=AsyncMock(side_effect=[draft(), review(*issues), draft("修正后"), review()]))
        ctx = turn_context()
        plan = await self.run_graph(model, context=ctx, references="[笔记 / 方法] 每天复习单词")
        self.assertEqual("修正后", plan.todos[0].title)
        correction = model.chat.call_args_list[2].args[0][-1]["content"]
        for issue in issues:
            self.assertIn(issue["message"], correction)
        for index in (1, 3):
            payload = json.loads(model.chat.call_args_list[index].args[0][1]["content"])
            self.assertEqual("安排英语复习", payload["requirement"])
            self.assertIn("每天复习单词", payload["references"])
        validation = [step for step in ctx.agent_steps if step.label == "校验规划约束"]
        self.assertEqual(["error", "success"], [step.status for step in validation])

    async def test_invalid_review_protocol_never_counts_as_passed(self):
        for raw in ('{}', '{"issues":"ok"}', '{"issues":[],"passed":false}', '不是 JSON'):
            with self.subTest(raw=raw):
                model = SimpleNamespace(chat=AsyncMock(side_effect=[draft(), AssistantMessage(content=raw)]))
                with self.assertRaises(BusinessException) as caught:
                    await self.run_graph(model)
                self.assertEqual(ErrorCode.PLAN_REVIEW_INVALID.code, caught.exception.code)

    async def test_semantic_rejections_stop_at_total_model_budget(self):
        issue = {"category": "constraint", "message": "仍未满足明确要求"}
        model = SimpleNamespace(chat=AsyncMock(side_effect=[draft(), review(issue)] * 3))
        ctx = turn_context()
        with self.assertRaises(BusinessException) as caught:
            await self.run_graph(model, context=ctx)
        self.assertEqual(ErrorCode.PLAN_LOOP_EXHAUSTED.code, caught.exception.code)
        self.assertEqual(MAX_PLAN_MODEL_ROUNDS, model.chat.await_count)
        self.assertEqual("规划循环达到上限", ctx.agent_steps[-1].label)

    async def test_endless_tool_requests_stop_and_backend_query_is_not_repeated(self):
        mcp = Mcp()
        model = SimpleNamespace(chat=AsyncMock(side_effect=[
            tool_calls("queryScheduleRange", prefix=f"round-{i}") for i in range(5)
        ]))
        with self.assertRaises(BusinessException) as caught:
            await self.run_graph(model, mcp=mcp)
        self.assertEqual(ErrorCode.PLAN_LOOP_EXHAUSTED.code, caught.exception.code)
        self.assertEqual(5, model.chat.await_count)
        self.assertIsNone(model.chat.call_args_list[-1].kwargs["tools"])
        self.assertEqual(1, len(mcp.calls))

    async def test_auth_failure_is_fatal_but_other_failures_are_error_evidence(self):
        for code in ("AUTH_FAILED", "INVALID_RESPONSE", "BUSINESS_ERROR"):
            with self.subTest(code=code):
                mcp = Mcp(failure=McpClientError(code, "private backend error"))
                model = SimpleNamespace(chat=AsyncMock(side_effect=[tool_calls("queryScheduleRange"), draft(), review()]))
                if code == "AUTH_FAILED":
                    with self.assertRaises(BusinessException) as caught:
                        await self.run_graph(model, mcp=mcp)
                    self.assertEqual(ErrorCode.MCP_AUTH_FAILED.code, caught.exception.code)
                    self.assertEqual(1, model.chat.await_count)
                else:
                    await self.run_graph(model, mcp=mcp)
                    tool_result = json.loads(model.chat.call_args_list[1].args[0][-1]["content"])
                    self.assertEqual(code, tool_result["error"])
                    self.assertNotIn("todos", tool_result)
                    self.assertNotIn("private backend error", str(tool_result))

    async def test_bad_bulk_query_result_is_not_treated_as_an_empty_calendar(self):
        for result in ({"todos": "invalid"}, {"todos": [{"todoId": 1, "title": "缺少日期"}]}):
            with self.subTest(result=result):
                model = SimpleNamespace(chat=AsyncMock(side_effect=[tool_calls("queryScheduleRange"), draft(), review()]))
                await self.run_graph(model, mcp=Mcp(result))
                evidence = json.loads(model.chat.call_args_list[1].args[0][-1]["content"])
                self.assertEqual("INVALID_RESPONSE", evidence["error"])

    async def test_repeated_failed_query_uses_error_summary_and_does_not_retry_backend(self):
        mcp = Mcp(failure=McpClientError("BUSINESS_ERROR", "private backend error"))
        model = SimpleNamespace(chat=AsyncMock(side_effect=[
            tool_calls("queryScheduleRange"), tool_calls("queryScheduleRange", prefix="again"), draft(), review(),
        ]))
        ctx = turn_context()
        await self.run_graph(model, context=ctx, mcp=mcp)
        self.assertEqual(1, len(mcp.calls))
        steps = [step for step in ctx.agent_steps if step.kind == "tool"]
        self.assertEqual(["error", "error"], [step.status for step in steps])
        self.assertIn("失败记录", steps[-1].resultSummary)
        self.assertNotIn("成功", steps[-1].resultSummary)

    async def test_run_cache_and_identity_are_isolated_between_users(self):
        mcp = Mcp()
        service = planning_service()
        model = SimpleNamespace(chat=AsyncMock(side_effect=[tool_calls("queryScheduleRange"), draft(), review()] * 2))
        graph = PlanningGraph(model, service, lambda: mcp)
        for user_id, token in ((7, "token-a"), (8, "token-b")):
            ctx = TurnContext(token=token, flow_state=SimpleNamespace(), started=perf_counter())
            await graph.run([{"role": "user", "content": "学习"}], user_id=user_id,
                            requirement="学习", context=ctx, date_range=day_range())
        self.assertEqual(["token-a", "token-b"], [call[2] for call in mcp.calls])


class PlanningIntegrationTests(unittest.IsolatedAsyncioTestCase):
    def state(self, **changes):
        return dict(user_id=7, session_id="s", message="参考文章学习英语", task="参考文章学习英语",
                    pending=PendingTask(task="旧需求", draft_id=11, plan_preview="旧草稿"),
                    signal=UserSignal.NEW_PLAN, planning_range=changes.pop("planning_range", day_range()), **changes)

    async def test_references_retrieved_once_before_model_and_reused_during_repairs(self):
        hit = SimpleNamespace(payload=SimpleNamespace(source="笔记", section="复习", text="每天背单词"))
        documents = SimpleNamespace(search=AsyncMock(return_value=[hit]))
        service = planning_service()
        model = SimpleNamespace(chat=AsyncMock(side_effect=[
            draft(), review({"category": "knowledge", "message": "缺少背单词任务"}), draft("背单词"), review(),
        ]))
        node = PlanNode(service, model, documents, EmptyScheduleMcp)
        state, ctx = self.state(document_ids=[42]), turn_context()
        result = await node(state, ctx)
        self.assertEqual(19, result.pending.draft_id)
        self.assertEqual(11, state["pending"].draft_id)
        documents.search.assert_awaited_once_with(7, [42], "参考文章学习英语")
        service.save_draft.assert_awaited_once()
        self.assertIn("背单词", service.save_draft.call_args.args[3].todos[0].title)
        for call in model.chat.call_args_list:
            self.assertIn("每天背单词", str(call.args[0]))
        labels = [step.label for step in ctx.agent_steps]
        self.assertLess(labels.index("检索引用文档"), labels.index("制定规划"))
        self.assertEqual(1, labels.count("检索引用文档"))

    async def test_failed_loop_preserves_old_draft_and_does_not_save(self):
        service = planning_service()
        model = SimpleNamespace(chat=AsyncMock(return_value=AssistantMessage(content="{}")))
        state = self.state()
        with self.assertRaises(BusinessException) as caught:
            await PlanNode(service, model, mcp_factory=EmptyScheduleMcp)(state, turn_context())
        self.assertEqual(ErrorCode.PLAN_LOOP_EXHAUSTED.code, caught.exception.code)
        self.assertEqual(3, model.chat.await_count)
        service.save_draft.assert_not_awaited()
        self.assertEqual("旧草稿", state["pending"].plan_preview)

    async def test_disconnect_after_review_prevents_saving(self):
        service, disconnected = planning_service(), False

        async def chat(messages, tools=None):
            nonlocal disconnected
            if messages[0]["content"].startswith("你是规划约束校验器"):
                disconnected = True
                return review()
            return draft()

        async def is_disconnected():
            return disconnected

        ctx = turn_context(is_disconnected=is_disconnected)
        with self.assertRaises(asyncio.CancelledError):
            await PlanNode(service, SimpleNamespace(chat=chat), mcp_factory=EmptyScheduleMcp)(self.state(), ctx)
        service.save_draft.assert_not_awaited()

    async def test_cancelling_active_bulk_query_closes_mcp_and_preserves_draft(self):
        started, closed = asyncio.Event(), asyncio.Event()

        class SlowMcp(Mcp):
            async def call_tool(self, *_):
                started.set()
                await asyncio.Event().wait()

            async def __aexit__(self, *_):
                closed.set()

        service = planning_service()
        model = SimpleNamespace(chat=AsyncMock(return_value=tool_calls("queryScheduleRange")))
        node = PlanNode(service, model, mcp_factory=SlowMcp)
        task = asyncio.create_task(node(self.state(), turn_context()))
        await asyncio.wait_for(started.wait(), timeout=2)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertTrue(closed.is_set())
        service.save_draft.assert_not_awaited()

    async def test_sse_publishes_retrieval_validation_repairs_and_commits_once(self):
        repo, service = MemoryFlowRepository(), planning_service()
        hit = SimpleNamespace(payload=SimpleNamespace(source="文章", section="正文", text="每天复习单词"))
        documents = SimpleNamespace(search=AsyncMock(return_value=[hit]))
        model = SimpleNamespace(chat=AsyncMock(side_effect=[
            tool_calls("queryScheduleRange", "queryPreferences"), draft(),
            review({"category": "constraint", "message": "private validation details"}),
            draft("修正后"), review(),
        ]))
        mcp = Mcp()

        def factory(**kwargs):
            chat = ChatService(repo)
            chat.document_references = AsyncMock(return_value=[])
            graph = ConversationGraph(**kwargs, chat_service=chat,
                summary_node=SummaryNode(service=SimpleNamespace(snapshot=AsyncMock(return_value=None))),
                route_agent=SimpleNamespace(route=AsyncMock(return_value=RouteDecision(signal=UserSignal.NEW_PLAN, planning_range=day_range()))))
            graph.handlers[AgentType.PLANNER] = PlanNode(service, model, documents, lambda: mcp)
            return graph

        events = [event async for event in ConversationTransport(factory).stream_reply(
            7, ChatRequest(sessionId="s", message="参考文章学习英语", documentIds=[42]),
            "secret-token", AsyncMock(return_value=False))]
        self.assertIsInstance(events[-2], ResultEvent)
        self.assertIsInstance(events[-1], DoneEvent)
        self.assertEqual(5, events[-1].data.rounds)
        self.assertEqual(1, len(repo.saved))
        service.save_draft.assert_awaited_once()
        documents.search.assert_awaited_once()
        steps = [event.data for event in events if isinstance(event, AgentStepEvent)]
        for label in ("检索引用文档", "查询规划区间日程", "查询用户偏好", "校验规划约束", "反馈修正规划", "保存规划草稿"):
            self.assertIn(label, [step.label for step in steps])
        self.assertTrue(any(step.label == "校验规划约束" and step.status == "error" for step in steps))
        public = str([step.model_dump() for step in steps])
        self.assertNotIn("secret-token", public)
        self.assertNotIn("private validation details", public)
        reply = "".join(event.data.delta for event in events if isinstance(event, AssistantDeltaEvent))
        self.assertIn("修正后", reply)
        self.assertNotIn('"todos"', reply)  # 原始 JSON 草稿不泄漏到正文流。
        persisted = repo.saved[0][2]["agent_steps"]
        self.assertEqual("保存规划草稿", persisted[-1].label)
        self.assertTrue(all(step.status != "running" for step in persisted))
