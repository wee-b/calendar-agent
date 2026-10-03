"""Route 信号解析及跨轮状态提交测试，不连接模型或数据库。"""

import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from app.schemas.statemachine.flow import AgentTurnResult, ConversationStage, PendingTask, UserSignal
from app.schemas.statemachine.route_context import RouteContext, RouteContextMessage
from app.schemas.statemachine.transitions import AgentType
from app.all_graph.conversation_graph import ConversationGraph
from app.all_graph.nodes.chat_node import ChatNode
from app.all_graph.nodes.route_node import RouteAgent, RouteDecision
from app.service.chat import ChatService


class FakeModel:
    def __init__(self, reply: str):
        self.reply = reply
        self.messages = None

    async def complete(self, messages):
        self.messages = messages
        return self.reply


class FakeRepository:
    def __init__(self):
        self.state = SimpleNamespace(
            stage="CHAT", pending_task=None, pending_draft_id=None,
            pending_payload=None, image_instruction=None, processing=0,
        )
        self.commits = []
        self.releases = 0

    async def get_or_create(self, user_id, session_id):
        return self.state

    async def claim(self, state):
        if state.processing:
            return False
        state.processing = 1
        return True

    async def complete(self, state, stage, pending, agent, **round_data):
        state.stage = stage.name
        state.pending_task = pending.task
        state.pending_draft_id = pending.draft_id
        state.pending_payload = pending.plan_preview
        state.image_instruction = pending.image_instruction
        state.processing = 0
        self.commits.append((stage, agent))

    async def release_claim(self, state):
        state.processing = 0
        self.releases += 1

    @staticmethod
    def resolve_stage(state):
        return ConversationStage[state.stage]

    @staticmethod
    def pending(state):
        return PendingTask(task=state.pending_task, draft_id=state.pending_draft_id,
                           plan_preview=state.pending_payload,
                           image_instruction=state.image_instruction)


class RouteAgentTests(unittest.IsolatedAsyncioTestCase):
    async def test_prompt_contains_stage_pending_and_continuous_messages(self):
        model = FakeModel('{"userSignal":"MODIFY","task":"晚上两小时"}')
        context = RouteContext(
            sessionId="s",
            previousReply=RouteContextMessage(role="assistant", content="初稿已生成"),
            userMessages=[
                RouteContextMessage(role="user", content="不要早起"),
            ],
        )
        class FakeChatService:
            calls = []

            async def get_route_context(self, user_id, session_id, current_message=None):
                self.calls.append((user_id, session_id, current_message))
                context.userMessages.append(RouteContextMessage(role="user", content=current_message))
                return context

        chat_service = FakeChatService()
        decision = await RouteAgent(model, chat_service).route(
            1, "s", "改到晚上", ConversationStage.PLAN,
            PendingTask(task="学习计划", draft_id=4, plan_preview="每天一小时"),
        )
        self.assertEqual([(1, "s", "改到晚上")], chat_service.calls)
        self.assertEqual(UserSignal.MODIFY, decision.signal)
        self.assertEqual("晚上两小时", decision.task)
        self.assertIn("会话阶段：PLAN", model.messages[0]["content"])
        self.assertIn("当前任务：学习计划", model.messages[0]["content"])
        self.assertIn("不要早起\n改到晚上", model.messages[1]["content"])
        self.assertIn("初稿已生成", model.messages[1]["content"])

    async def test_invalid_signal_never_authorizes_write(self):
        for raw in ('{"userSignal":"READY_EXECUTE"}', "not json", '{"userSignal":3}'):
            self.assertEqual(UserSignal.UNKNOWN, RouteAgent.parse(raw).signal)


class ConversationGraphTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.repository = FakeRepository()
        self.decision = RouteDecision(signal=UserSignal.NEW_PLAN, task="制定学习计划")
        self.calls = []

        class FakeRoute:
            async def route(_, user_id, session_id, message, stage, pending):
                self.calls.append(("route", stage, pending.task))
                return self.decision

        async def handler(state, context):
            self.calls.append((state["agent"], state["signal"], state["stage"], state["task"]))
            return AgentTurnResult(reply="已处理", completed=True,
                                   pending=PendingTask(task=state["task"] or state["pending"].task),
                                   dispatch_type="TEST")

        self.handlers = {agent: handler for agent in AgentType}
        self.route = FakeRoute()

    def graph(self, handlers=None):
        return ConversationGraph(
            handlers if handlers is not None else self.handlers,
            user_id=1, session_id="s",
            chat_service=ChatService(self.repository), route_agent=self.route,
        )

    async def test_multiple_turns_use_persisted_stage_and_preserve_pending_on_query(self):
        graph = self.graph()
        first = await graph.run_turn("做一个学习计划", "token")
        self.assertEqual(ConversationStage.PLAN, first.stage)
        self.assertEqual(AgentType.PLANNER, first.agent)
        self.decision = RouteDecision(signal=UserSignal.NEW_QUERY, task="查明天安排")
        second = await graph.run_turn("另外查明天安排", "token")
        self.assertEqual(AgentType.CHAT, second.agent)
        self.assertEqual(ConversationStage.PLAN, second.stage)
        self.assertEqual("制定学习计划", self.repository.state.pending_task)
        self.assertEqual(("route", ConversationStage.PLAN, "制定学习计划"), self.calls[2])

    async def test_uncompleted_turn_keeps_previous_stage(self):
        self.repository.state.stage = "EXECUTE"

        async def handler(state, context):
            return AgentTurnResult(reply="请补充信息", completed=False,
                                   pending=PendingTask(task="原任务"), dispatch_type="TEST")

        self.decision = RouteDecision(signal=UserSignal.NEW_PLAN, task="计划")
        result = await self.graph({AgentType.PLANNER: handler}).run_turn("计划", "token")
        self.assertEqual(ConversationStage.EXECUTE, result.stage)
        self.assertEqual("EXECUTE", self.repository.state.stage)

    async def test_failure_before_write_releases_claim(self):
        async def handler(state, context):
            raise RuntimeError("模型错误")

        with self.assertRaises(RuntimeError):
            await self.graph({AgentType.PLANNER: handler}).run_turn("计划", "token")
        self.assertEqual(1, self.repository.releases)
        self.assertEqual(0, self.repository.state.processing)

    async def test_uncertain_write_keeps_claim(self):
        self.decision = RouteDecision(signal=UserSignal.NEW_EXECUTE, task="新增日程")

        async def handler(state, context):
            context.mark_write_started()
            raise RuntimeError("写操作结果不确定")

        from app.core.exception.exceptions import BusinessException
        with self.assertRaises(BusinessException):
            await self.graph({AgentType.EXECUTOR: handler}).run_turn("新增日程", "token")
        self.assertEqual(0, self.repository.releases)
        self.assertEqual(1, self.repository.state.processing)


class ChatNodeIntegrationTests(unittest.IsolatedAsyncioTestCase):
    async def test_cancel_is_deterministic_and_does_not_save_before_commit(self):
        service = SimpleNamespace(
            recent_messages=AsyncMock(return_value=[]), save_round=AsyncMock(),
        )
        graph = SimpleNamespace(
            run=AsyncMock(return_value="已取消当前任务"), agent_label="chatAgent(test)",
        )
        context = SimpleNamespace(token="token", mark_write_started=Mock())
        state = {
            "user_id": 1, "session_id": "s", "message": "取消",
            "signal": UserSignal.REJECT, "pending": PendingTask(task="旧任务"),
        }
        with patch("app.all_graph.nodes.chat_node.ChatGraph", return_value=graph):
            result = await ChatNode(service)(state, context)
        context.mark_write_started.assert_not_called()
        service.save_round.assert_not_awaited()
        graph.run.assert_not_awaited()
        self.assertIsNone(result.pending.task)
        self.assertEqual("CANCEL", result.dispatch_type)
