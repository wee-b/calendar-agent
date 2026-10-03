from app.all_graph.nodes.summary_node import SummaryNode
"""迁移节点的行为回归：确认范围、草稿隔离、失败保留及跨轮状态。"""

import asyncio
import json
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import httpx

from app.all_graph.conversation_graph import ConversationGraph, TurnContext
from app.all_graph.nodes.execute_node import ExecuteNode
from app.all_graph.nodes.image_node import ImageNode
from app.all_graph.nodes.plan_node import PlanNode, should_clarify
from app.all_graph.nodes.route_node import RouteDecision
from app.core.config.agent.agents import ImageAgentConfig
from app.core.config.agent.providers import ModelConfig
from app.core.exception.exceptions import BusinessException, McpClientError
from app.helper.image_client import ImageClient
from app.schemas.chat import ChatRequest
from app.schemas.chat.model_stream import AssistantMessage, ModelDelta, ToolCall, ToolFunction
from app.schemas.executor_tools import READ_TOOLS
from app.schemas.mcp import McpToolDefinition, McpToolMetadata
from app.schemas.plan import parse_plan, today
from app.schemas.statemachine.flow import ConversationStage as Stage, PendingTask, UserSignal as Signal
from app.schemas.statemachine.transitions import AgentType
from app.service.chat import ChatService
from app.api.conversation_transport import ConversationTransport
from app.service.planning import PlanningService
from tests.conversation_fakes import MemoryFlowRepository


def plan_json(title="英语"):
    day = today()
    return json.dumps({"goal": "学习", "startDate": str(day), "endDate": str(day),
                       "todos": [{"title": title, "startDate": str(day), "endDate": str(day),
                                  "weekDays": [day.isoweekday()]}]}, ensure_ascii=False)


def turn(signal=Signal.NEW_PLAN, stage=Stage.CHAT, pending=None, **kwargs):
    return dict(user_id=7, session_id="s", message="做一个学习计划", signal=signal,
                stage=stage, pending=pending or PendingTask(), **kwargs)


def context(emit=None):
    return TurnContext(token="user-token", flow_state=SimpleNamespace(), emit=emit)


def calls(*specs):
    return AssistantMessage(tool_calls=[ToolCall(id=f"call-{i}", function=ToolFunction(
        name=name, arguments=json.dumps(args))) for i, (name, args) in enumerate(specs)])


class Mcp:
    def __init__(self, names, result=None, failure=None):
        self.names, self.result, self.failure = names, result, failure
        self.calls = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        pass

    async def list_tools(self, token):
        assert token == "user-token"
        return [McpToolDefinition(name=name, inputSchema={"type": "object"},
                metadata=McpToolMetadata(readOnly=name in READ_TOOLS, timeoutMs=5000)) for name in self.names]

    async def call_tool(self, name, arguments, token):
        self.calls.append((name, arguments, token))
        if self.failure:
            raise self.failure
        return self.result or {"operation": "deleted", "todoId": 3}


class PlanNodeTests(unittest.IsolatedAsyncioTestCase):
    async def test_generate_then_modify_preserves_old_draft_until_success(self):
        planning = SimpleNamespace(memories=AsyncMock(return_value=[]),
                                   save_draft=AsyncMock(return_value=SimpleNamespace(draft_id=12)))
        model = SimpleNamespace(complete=AsyncMock(return_value=plan_json()))
        node = PlanNode(planning, model)
        original = PendingTask(task="英语复习", draft_id=11, plan_preview="旧正文", image_instruction="蓝色")
        state = turn(Signal.MODIFY, Stage.PLAN, original)
        state["message"] = "每天两小时"
        result = await node(state, context())
        self.assertEqual(12, result.pending.draft_id)
        prompt = model.complete.call_args.args[0][1]["content"]
        self.assertIn("英语复习", prompt)
        self.assertIn("每天两小时", prompt)
        self.assertIn("每天两小时", planning.save_draft.call_args.args[2])
        self.assertIsNone(result.pending.image_instruction)
        self.assertEqual(11, original.draft_id)
        model.complete.side_effect = RuntimeError("failed")
        with self.assertRaises(RuntimeError):
            await node(state, context())
        self.assertEqual("旧正文", original.plan_preview)

    async def test_existing_draft_confirmation_does_not_generate_or_sync(self):
        planning = SimpleNamespace(save_draft=AsyncMock(), memories=AsyncMock())
        model = SimpleNamespace(complete=AsyncMock())
        result = await PlanNode(planning, model)(turn(Signal.CONFIRM, Stage.PLAN, PendingTask(draft_id=8)), context())
        self.assertEqual("PLAN_FEEDBACK", result.dispatch_type)
        model.complete.assert_not_awaited()
        planning.save_draft.assert_not_awaited()
        planning.memories.assert_not_awaited()

    async def test_clarification_keeps_requirement_then_confirm_generates(self):
        planning = SimpleNamespace(memories=AsyncMock(return_value=[]),
                                   save_draft=AsyncMock(return_value=SimpleNamespace(draft_id=1)))
        model = SimpleNamespace(complete=AsyncMock(return_value=plan_json()))
        node = PlanNode(planning, model)
        state = turn(task="制定计划")  # Java hashCode('s') % 3 == 1
        result = await node(state, context())
        self.assertEqual("PLAN_CLARIFICATION", result.dispatch_type)
        self.assertEqual("制定计划", result.pending.task)
        self.assertIsNone(result.pending.draft_id)
        model.complete.assert_not_awaited()
        result = await node(turn(Signal.CONFIRM, Stage.PLAN, result.pending), context())
        self.assertEqual(1, result.pending.draft_id)

    async def test_invalid_model_json_is_corrected_once_and_saved_once(self):
        model = SimpleNamespace(complete=AsyncMock(side_effect=["not json", plan_json()]))
        drafts = SimpleNamespace(save=AsyncMock(return_value=SimpleNamespace(draft_id=5)))
        service = PlanningService(drafts=drafts, memory=SimpleNamespace(relevant=AsyncMock(return_value=[])))
        result = await PlanNode(service, model)(turn(task="英语学习"), context())
        self.assertEqual(5, result.pending.draft_id)
        self.assertIn("英语", result.pending.plan_preview)
        self.assertEqual(2, model.complete.await_count)
        drafts.save.assert_awaited_once()

    async def test_second_invalid_plan_never_saves(self):
        drafts = SimpleNamespace(save=AsyncMock())
        service = PlanningService(drafts=drafts, memory=SimpleNamespace(relevant=AsyncMock(return_value=[])))
        node = PlanNode(service, SimpleNamespace(complete=AsyncMock(return_value="{}")))
        with self.assertRaises(BusinessException):
            await node(turn(task="英语学习"), context())
        drafts.save.assert_not_awaited()

    def test_validation_rejects_invalid_dates_weekdays_and_identity(self):
        for change in ({"weekDays": [8]}, {"userId": 99}, {"endDate": "2000-01-01"}, {"title": " "}):
            data = json.loads(plan_json())
            data["todos"][0].update(change)
            with self.subTest(change=change), self.assertRaises(ValueError):
                parse_plan(json.dumps(data), generating=True)
        self.assertFalse(should_clarify("制定计划", "s", [{"memory_type": "PREFERENCE_TIME"}]))


class ExecuteNodeTests(unittest.IsolatedAsyncioTestCase):
    async def test_prepare_modify_then_confirm_uses_saved_task_not_route_task(self):
        model = SimpleNamespace(chat=AsyncMock(side_effect=[calls(("deleteTodo", {"todoId": 3})),
                                                           AssistantMessage(content="删除完成")]))
        mcp = Mcp(["deleteTodo"])
        node, ctx = ExecuteNode(model, lambda: mcp), context()
        prepared = await node(turn(Signal.NEW_EXECUTE, task="删除英语待办"), ctx)
        state = turn(Signal.MODIFY, Stage.EXECUTE, prepared.pending)
        state["message"] = "改成数学待办"
        modified = await node(state, ctx)
        model.chat.assert_not_awaited()
        self.assertFalse(ctx.write_started)
        await node(turn(Signal.CONFIRM, Stage.EXECUTE, modified.pending, task="删除所有日程"), ctx)
        prompt = model.chat.call_args_list[0].args[0][1]["content"]
        self.assertIn("改成数学待办", prompt)
        self.assertNotIn("删除所有日程", prompt)
        self.assertTrue(ctx.write_started)
        self.assertEqual(1, len(mcp.calls))

    async def test_query_or_followup_without_write_keeps_pending(self):
        model = SimpleNamespace(chat=AsyncMock(side_effect=[calls(("queryTodoList", {})),
                                                           AssistantMessage(content="请说明哪个目标")]))
        ctx, pending = context(), PendingTask(task="删除一个待办")
        result = await ExecuteNode(model, lambda: Mcp(["queryTodoList"]))(turn(Signal.CONFIRM, Stage.EXECUTE, pending), ctx)
        self.assertFalse(result.completed)
        self.assertEqual(pending, result.pending)
        self.assertFalse(ctx.write_started)

    async def test_validate_all_calls_before_first_write_and_reject_user_id(self):
        for bad in ({"todoId": 4, "userId": 99}, {"todoId": -1}):
            mcp, ctx = Mcp(["deleteTodo"]), context()
            node = ExecuteNode(SimpleNamespace(chat=AsyncMock(return_value=calls(
                ("deleteTodo", {"todoId": 3}), ("deleteTodo", bad)))), lambda: mcp)
            with self.assertRaises(BusinessException):
                await node(turn(Signal.CONFIRM, Stage.EXECUTE, PendingTask(task="删除")), ctx)
            self.assertEqual([], mcp.calls)
            self.assertFalse(ctx.write_started)

    async def test_write_failure_is_not_retried(self):
        mcp, ctx = Mcp(["deleteTodo"], failure=McpClientError("JAVA_TIMEOUT", "timeout", retryable=True)), context()
        node = ExecuteNode(SimpleNamespace(chat=AsyncMock(return_value=calls(("deleteTodo", {"todoId": 3})))), lambda: mcp)
        with self.assertRaises(McpClientError):
            await node(turn(Signal.CONFIRM, Stage.EXECUTE, PendingTask(task="删除")), ctx)
        self.assertEqual(1, len(mcp.calls))
        self.assertTrue(ctx.write_started)

    async def test_malformed_write_result_never_reports_success(self):
        for result in ({"operation": "deleted", "todoId": 99}, {"message": "success"}):
            mcp, ctx = Mcp(["deleteTodo"], result=result), context()
            node = ExecuteNode(SimpleNamespace(chat=AsyncMock(return_value=calls(("deleteTodo", {"todoId": 3})))), lambda: mcp)
            with self.assertRaises(BusinessException):
                await node(turn(Signal.CONFIRM, Stage.EXECUTE, PendingTask(task="删除")), ctx)
            self.assertEqual(1, len(mcp.calls))
            self.assertTrue(ctx.write_started)

    async def test_repeated_write_from_next_model_round_is_blocked(self):
        mcp, ctx = Mcp(["deleteTodo"]), context()
        node = ExecuteNode(SimpleNamespace(chat=AsyncMock(return_value=calls(("deleteTodo", {"todoId": 3})))), lambda: mcp)
        with self.assertRaises(BusinessException):
            await node(turn(Signal.CONFIRM, Stage.EXECUTE, PendingTask(task="删除")), ctx)
        self.assertEqual(1, len(mcp.calls))

    async def test_sync_uses_exact_draft_and_one_transactional_batch_call(self):
        drafts = SimpleNamespace(find_pending=AsyncMock(return_value=SimpleNamespace(draft_id=8, plan_json=plan_json())),
                                 mark_synced=AsyncMock())
        mcp = Mcp(["batchCreateTodos"], result={"createdCount": 1, "createdTodos": [{"todoId": 5, "title": "英语"}]})
        node = ExecuteNode(mcp_factory=lambda: mcp, drafts=drafts)
        result = await node(turn(Signal.SYNC_PLAN, Stage.IMAGE, PendingTask(draft_id=8)), context())
        drafts.find_pending.assert_awaited_once_with(7, "s", 8)
        self.assertEqual("batchCreateTodos", mcp.calls[0][0])
        self.assertNotIn("draftId", mcp.calls[0][1])
        drafts.mark_synced.assert_awaited_once_with(7, "s", 8)
        self.assertTrue(result.completed)
        self.assertIsNone(result.pending.draft_id)

    async def test_missing_draft_does_not_fallback_or_call_mcp(self):
        drafts = SimpleNamespace(find_pending=AsyncMock(return_value=None))
        node = ExecuteNode(mcp_factory=lambda: self.fail("must not call MCP"), drafts=drafts)
        result = await node(turn(Signal.SYNC_PLAN, Stage.PLAN, PendingTask(draft_id=8)), context())
        self.assertFalse(result.completed)
        self.assertEqual(8, result.pending.draft_id)

    async def test_executor_stream_assembles_arguments_and_redacts_tool_events(self):
        class Model:
            rounds = 0

            async def stream_chat(self, messages, tools=None):
                self.rounds += 1
                if self.rounds == 1:
                    yield ModelDelta.model_validate({"tool_calls": [{"index": 0, "id": "x", "function": {
                        "name": "deleteTodo", "arguments": '{"todo'}}]})
                    yield ModelDelta.model_validate({"tool_calls": [{"index": 0, "function": {"arguments": 'Id":3}'}}]})
                else:
                    yield ModelDelta(content="删除")
                    yield ModelDelta(content="完成")
        events, mcp = [], Mcp(["deleteTodo"])
        async def emit(event):
            events.append(event)
        result = await ExecuteNode(Model(), lambda: mcp)(turn(Signal.CONFIRM, Stage.EXECUTE, PendingTask(task="删除")), context(emit))
        self.assertEqual("删除完成", result.reply)
        self.assertEqual(1, len(mcp.calls))
        self.assertIn("tool_call_end", [event.event for event in events])
        self.assertNotIn("todoId", json.dumps([event.model_dump() for event in events]))


class ImageNodeTests(unittest.IsolatedAsyncioTestCase):
    async def test_image_feedback_keeps_original_draft_and_accumulates_instruction(self):
        drafts = SimpleNamespace(find_pending=AsyncMock(return_value=SimpleNamespace(plan_json=plan_json())))
        client = SimpleNamespace(generate=AsyncMock(return_value="https://example.com/plan.jpg"))
        pending = PendingTask(task="英语", draft_id=8, plan_preview="原规划", image_instruction="蓝色")
        state = turn(Signal.MODIFY, Stage.IMAGE, pending)
        state["message"] = "字体大一点"
        result = await ImageNode(client, drafts)(state, context())
        self.assertEqual(8, result.pending.draft_id)
        self.assertEqual("原规划", result.pending.plan_preview)
        self.assertIn("蓝色", result.pending.image_instruction)
        self.assertIn("字体大一点", result.pending.image_instruction)
        client.generate.side_effect = RuntimeError("failed")
        with self.assertRaises(RuntimeError):
            await ImageNode(client, drafts)(state, context())
        self.assertEqual("蓝色", pending.image_instruction)

    async def test_image_http_protocol_and_limit_error(self):
        config = ImageAgentConfig(model=ModelConfig("ark", "test-image", "https://example.com/v3", "secret", "image-generation"))
        def handle(request):
            self.assertEqual("/v3/images/generations", request.url.path)
            payload = json.loads(request.content)
            self.assertEqual("2K", payload["size"])
            self.assertFalse(payload["stream"])
            self.assertTrue(payload["watermark"])
            return httpx.Response(200, json={"data": [{"url": "https://example.com/a.jpg"}]})
        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
            self.assertEqual("https://example.com/a.jpg", await ImageClient(config, client).generate(plan_json(), "蓝色"))
        async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(
            400, json={"error": {"code": "SetLimitExceeded"}}))) as client:
            with self.assertRaises(BusinessException) as error:
                await ImageClient(config, client).generate(plan_json(), "")
            self.assertEqual(429, error.exception.status_code)


class NodeGraphTests(unittest.IsolatedAsyncioTestCase):
    async def test_plan_image_sync_across_turns_and_temporary_query(self):
        repo = MemoryFlowRepository()
        route = SimpleNamespace(route=AsyncMock(return_value=RouteDecision(signal=Signal.NEW_PLAN)))
        planning = SimpleNamespace(memories=AsyncMock(return_value=[]),
                                   save_draft=AsyncMock(return_value=SimpleNamespace(draft_id=8)))
        drafts = SimpleNamespace(find_pending=AsyncMock(return_value=SimpleNamespace(draft_id=8, plan_json=plan_json())),
                                 mark_synced=AsyncMock())
        mcp = Mcp(["batchCreateTodos"], result={"createdCount": 1, "createdTodos": [{"todoId": 9, "title": "英语"}]})
        graph = ConversationGraph(user_id=7, session_id="s", chat_service=ChatService(repo), summary_node=SummaryNode(service=SimpleNamespace(snapshot=AsyncMock(return_value=None))), route_agent=route)
        graph.handlers[AgentType.PLANNER] = PlanNode(planning, SimpleNamespace(complete=AsyncMock(return_value=plan_json())))
        graph.handlers[AgentType.IMAGE] = ImageNode(SimpleNamespace(generate=AsyncMock(return_value="https://example.com/x.jpg")), drafts)
        graph.handlers[AgentType.EXECUTOR] = ExecuteNode(mcp_factory=lambda: mcp, drafts=drafts)
        self.assertEqual(Stage.PLAN, (await graph.run_turn("英语学习", "user-token")).stage)
        route.route.return_value = RouteDecision(signal=Signal.GENERATE_PLAN_IMAGE)
        image = await graph.run_turn("生成图片", "user-token")
        self.assertEqual(Stage.IMAGE, image.stage)
        self.assertEqual(8, image.pending.draft_id)
        route.route.return_value = RouteDecision(signal=Signal.CONFIRM)
        self.assertEqual(Stage.IMAGE, (await graph.run_turn("好的", "user-token")).stage)
        self.assertFalse(mcp.calls)
        route.route.return_value = RouteDecision(signal=Signal.SYNC_PLAN)
        self.assertEqual(Stage.CHAT, (await graph.run_turn("同步到日历", "user-token")).stage)
        self.assertEqual(4, len(repo.saved))
        self.assertEqual(1, len(mcp.calls))

    async def test_two_concurrent_confirmations_only_one_write_and_failure_locks(self):
        repo = MemoryFlowRepository()
        state = await repo.get_or_create(7, "s")
        state.stage, state.pending_task = "EXECUTE", "删除英语"
        entered, finish = asyncio.Event(), asyncio.Event()
        async def model_call(*_, **__):
            entered.set()
            await finish.wait()
            return calls(("deleteTodo", {"todoId": 3}))
        route = SimpleNamespace(route=AsyncMock(return_value=RouteDecision(signal=Signal.CONFIRM)))
        mcp = Mcp(["deleteTodo"], failure=McpClientError("JAVA_TIMEOUT", "unknown"))
        graph = ConversationGraph({AgentType.EXECUTOR: ExecuteNode(SimpleNamespace(chat=model_call), lambda: mcp)},
                                  user_id=7, session_id="s", chat_service=ChatService(repo), summary_node=SummaryNode(service=SimpleNamespace(snapshot=AsyncMock(return_value=None))), route_agent=route)
        first = asyncio.create_task(graph.run_turn("确认", "user-token"))
        await entered.wait()
        with self.assertRaises(BusinessException):
            await graph.run_turn("确认", "user-token")
        finish.set()
        with self.assertRaises(BusinessException):
            await first
        self.assertEqual(1, state.processing)
        self.assertEqual(0, repo.releases)
        self.assertEqual(1, len(mcp.calls))
        self.assertEqual([], repo.saved)

    async def test_stream_disconnect_cancels_node_and_releases_claim(self):
        repo = MemoryFlowRepository()
        closed = asyncio.Event()
        async def slow_node(state, ctx):
            try:
                await asyncio.Event().wait()
            finally:
                closed.set()
        def factory(**kwargs):
            return ConversationGraph({AgentType.PLANNER: slow_node}, **kwargs, chat_service=ChatService(repo),
                summary_node=SummaryNode(service=SimpleNamespace(snapshot=AsyncMock(return_value=None))), route_agent=SimpleNamespace(route=AsyncMock(return_value=RouteDecision(signal=Signal.NEW_PLAN))))
        stream = ConversationTransport(factory).stream_reply(7, ChatRequest(sessionId="s", message="学习"), "user-token",
                                                         AsyncMock(return_value=False))
        await anext(stream)  # route 状态
        await anext(stream)  # planner 状态
        await asyncio.sleep(0)
        await stream.aclose()
        self.assertEqual(0, repo.states[7, "s"].processing)
        self.assertEqual([], repo.saved)


if __name__ == "__main__":
    unittest.main()
