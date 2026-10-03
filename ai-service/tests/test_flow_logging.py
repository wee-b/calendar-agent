import asyncio
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from app.all_graph.conversation_graph import ConversationGraph
from app.all_graph.nodes.chat_node import ChatNode
from app.all_graph.nodes.route_node import RouteDecision
from app.core.exception.exceptions import BusinessException
from app.core.flow_logging import log_turn_step
from app.schemas.chat.model_stream import AssistantMessage, ModelDelta, ToolCall, ToolFunction
from app.schemas.statemachine.flow import AgentTurnResult, PendingTask, UserSignal
from app.schemas.statemachine.transitions import AgentType
from app.service.chat import ChatService
from tests.conversation_fakes import MemoryFlowRepository


class FlowLoggingTests(unittest.IsolatedAsyncioTestCase):
    def graph(self, node, *, session="s", repo=None, signal=UserSignal.NEW_PLAN):
        return ConversationGraph(
            {AgentType.PLANNER: node, AgentType.CHAT: node}, user_id=7, session_id=session,
            chat_service=ChatService(repo or MemoryFlowRepository()),
            route_agent=SimpleNamespace(route=AsyncMock(return_value=RouteDecision(signal=signal))),
        )

    async def test_committed_session_changes_and_turn_ids_across_inputs(self):
        node = AsyncMock(return_value=AgentTurnResult(reply="草稿", completed=True,
                        pending=PendingTask(task="学习", draft_id=8), dispatch_type="PLAN"))
        graph = self.graph(node)
        with self.assertLogs("app", level="INFO") as logs:
            await graph.run_turn("学习\n" + "计划" * 20, "secret-token")
            graph.route_agent.route.return_value = RouteDecision(signal=UserSignal.NEW_QUERY)
            node.return_value = AgentTurnResult(reply="查询结果", completed=True,
                                               pending=PendingTask(), dispatch_type="QUERY")
            await graph.run_turn("另外查明天的安排", "secret-token")
        self.assertEqual(6, len(logs.records))
        self.assertEqual(["会话流转", "单轮流转", "对话完成"] * 2,
                         [r.getMessage().split(" | ", 1)[0] for r in logs.records])
        commits = [r for r in logs.records if getattr(r, "flow_scope", None) == "会话流转"]
        self.assertEqual([("CHAT", "PLAN"), ("PLAN", "PLAN")], [(r.from_state, r.to_state) for r in commits])
        self.assertEqual(8, commits[1].flow_details["新任务"]["草稿ID"])
        self.assertEqual(2, len({r.turn_id for r in commits}))
        self.assertNotIn("secret-token", "\n".join(logs.output))
        first = next(r for r in logs.records if getattr(r, "flow_scope", None) == "单轮流转")
        self.assertEqual("END", first.to_state)
        self.assertEqual(["RECEIVED", "LOAD_STATE", "CLAIM", "ROUTE",
                          "TRANSITION(信号=NEW_PLAN,节点=PLANNER)", "PLANNER",
                          "PLANNER_RESULT(完成=True,分发类型=PLAN)", "COMMIT",
                          "END(分发类型=PLAN,最终阶段=PLAN)"],
                         first.flow_details["路径"].split(" → "))

    async def test_no_logs_are_emitted_before_turn_finishes(self):
        entered = asyncio.Event()
        resume = asyncio.Event()

        async def node(state, context):
            entered.set()
            await resume.wait()
            return AgentTurnResult(reply="完成", completed=True, pending=PendingTask(), dispatch_type="PLAN")

        with self.assertLogs("app", level="INFO") as logs:
            task = asyncio.create_task(self.graph(node).run_turn("学习", "token"))
            await entered.wait()
            self.assertEqual([], logs.records)
            resume.set()
            await task
        self.assertEqual(3, len(logs.records))

    async def test_failure_and_disconnect_never_log_successful_commit(self):
        for write, cancel in ((False, False), (True, False), (False, True), (True, True)):
            with self.subTest(write=write, cancel=cancel):
                repo = MemoryFlowRepository()
                entered = asyncio.Event()
                async def fail(state, context):
                    if write:
                        context.mark_write_started()
                    if cancel:
                        entered.set()
                        await asyncio.Event().wait()
                    raise RuntimeError("exception-with-secret-token")
                with self.assertLogs("app", level="INFO") as logs:
                    with self.assertRaises(asyncio.CancelledError if cancel else (BusinessException if write else RuntimeError)):
                        task = asyncio.create_task(self.graph(fail, repo=repo).run_turn("学习", "secret-token"))
                        if cancel:
                            await entered.wait()
                            task.cancel()
                        await task
                self.assertEqual(3, len(logs.records))
                self.assertEqual("写结果待核实" if write else "未提交",
                                 logs.records[0].flow_details["结果"])
                if write:
                    self.assertEqual(1, logs.records[0].getMessage().count("写结果待核实"))
                self.assertEqual("CANCELLED" if cancel else "FAILED", logs.records[1].to_state)
                self.assertTrue(logs.records[2].getMessage().startswith("对话结束 |"))
                self.assertEqual(int(write), repo.states[7, "s"].processing)
                self.assertNotIn("secret-token", "\n".join(logs.output))

    async def test_commit_failure_logs_proposed_stage_but_never_committed_stage(self):
        repo = MemoryFlowRepository()
        repo.complete = AsyncMock(side_effect=RuntimeError("database unavailable"))
        node = AsyncMock(return_value=AgentTurnResult(reply="草稿", completed=True,
                        pending=PendingTask(draft_id=8), dispatch_type="PLAN"))
        with self.assertLogs("app", level="INFO") as logs:
            with self.assertRaises(RuntimeError):
                await self.graph(node, repo=repo).run_turn("学习", "token")
        self.assertEqual(3, len(logs.records))
        self.assertEqual("未提交", logs.records[0].flow_details["结果"])
        self.assertEqual("PLAN", logs.records[0].flow_details["目标阶段"])
        self.assertIn("目标 PLAN", logs.records[0].getMessage())
        self.assertEqual(("START", "FAILED"), (logs.records[1].from_state, logs.records[1].to_state))
        self.assertIn("COMMIT → FAILED", logs.records[1].flow_details["路径"])
        self.assertEqual("CHAT", repo.states[7, "s"].stage)

    async def test_busy_session_is_logged_as_blocked_without_entering_route(self):
        repo = MemoryFlowRepository()
        (await repo.get_or_create(7, "s")).processing = 1
        graph = self.graph(AsyncMock(), repo=repo)
        with self.assertLogs("app", level="INFO") as logs:
            with self.assertRaises(BusinessException):
                await graph.run_turn("学习", "token")
        self.assertEqual(3, len(logs.records))
        self.assertEqual("认领冲突", logs.records[0].flow_details["结果"])
        self.assertEqual("BLOCKED", logs.records[1].to_state)
        graph.route_agent.route.assert_not_awaited()

    async def test_concurrent_requests_keep_independent_trace_contexts(self):
        async def node(state, context):
            await asyncio.sleep(0)
            log_turn_step("CUSTOM_STEP", 会话=state["session_id"])
            return AgentTurnResult(reply="完成", completed=True, pending=PendingTask(), dispatch_type="PLAN")
        with self.assertLogs("app", level="INFO") as logs:
            await asyncio.gather(self.graph(node, session="a").run_turn("学习", "token"),
                                 self.graph(node, session="b").run_turn("工作", "token"))
        for session in ("a", "b"):
            records = [r for r in logs.records if getattr(r, "session_id", None) == session]
            self.assertEqual(1, len({r.turn_id for r in records}))
            self.assertEqual(2, len(records))
            self.assertIn("CUSTOM_STEP", records[1].flow_details["路径"])
        self.assertEqual(6, len(logs.records))
        self.assertEqual(2, len({r.turn_id for r in logs.records if hasattr(r, "turn_id")}))
        with self.assertNoLogs("app.flow", level="INFO"):
            log_turn_step("OUTSIDE_REQUEST")

    async def test_chat_model_tool_loop_logged_in_both_response_modes(self):
        class Model:
            def __init__(self):
                self.round = 0

            async def chat(self, *args, **kwargs):
                self.round += 1
                if self.round == 1:
                    return AssistantMessage(tool_calls=[ToolCall(id="call-1", function=ToolFunction(
                        name="queryDayDetail", arguments='{"date":"2026-10-03"}'))])
                return AssistantMessage(content="没有安排")

            async def stream_chat(self, *args, **kwargs):
                response = await self.chat()
                if response.tool_calls:
                    yield ModelDelta.model_validate({"tool_calls": [{"index": 0, "id": "call-1", "function":
                        {"name": "queryDayDetail", "arguments": '{"date":"2026-10-03"}'}}]})
                else:
                    yield ModelDelta(content=response.content)

        class Mcp:
            async def __aenter__(self):
                return self

            async def __aexit__(self, *_):
                pass

            async def call_tool(self, *_):
                return {"date": "2026-10-03", "todos": [], "dailyNote": "工具原始内容不写日志"}

        for streaming in (False, True):
            with self.subTest(streaming=streaming):
                node = ChatNode(SimpleNamespace(recent_messages=AsyncMock(return_value=[])))
                with patch("app.all_graph.chat_graph.ModelClient", return_value=Model()), \
                     patch("app.all_graph.chat_graph.JavaMcpClient", Mcp), \
                     self.assertLogs("app", level="INFO") as logs:
                    await self.graph(node, signal=UserSignal.NEW_QUERY).run_turn(
                        "查询日程", "secret-token", emit=AsyncMock() if streaming else None)
                self.assertEqual(3, len(logs.records))
                path = logs.records[1].flow_details["路径"]
                self.assertLess(path.index("CHAT_MODEL"), path.index("CHAT_TOOL"))
                self.assertIn("CHAT_TOOL_RESULT", path)
                self.assertIn("CHAT_RESULT", path)
                self.assertEqual(1, len({r.turn_id for r in logs.records if hasattr(r, "turn_id")}))
                self.assertIn("查询日程(成功)", logs.records[1].getMessage())
                self.assertNotIn("{", logs.records[1].getMessage())
                self.assertNotIn("工具原始内容不写日志", "\n".join(logs.output))
