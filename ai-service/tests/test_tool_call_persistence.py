"""沿用随机临时 MySQL 库，验证迁移、P95 与用户隔离。"""

import asyncio
from datetime import datetime, timedelta
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from sqlalchemy import select, text

from app.core.exception.exceptions import BusinessException
from app.models.tool_call import ToolCall
from app.repository.tool_call import ToolCallRepository
from app.repository.flow_state import FlowStateRepository
from app.all_graph.conversation_graph import ConversationGraph
from app.all_graph.nodes.execute_node import ExecuteNode
from app.all_graph.nodes.route_node import RouteDecision
from app.all_graph.nodes.summary_node import SummaryNode
from app.core.exception.error_code import ErrorCode
from app.core.exception.exceptions import McpClientError
from app.schemas.chat.model_stream import AssistantMessage, ToolCall as ModelToolCall, ToolFunction
from app.schemas.mcp import McpToolDefinition, McpToolMetadata
from app.schemas.statemachine.flow import UserSignal
from app.schemas.statemachine.transitions import AgentType
from app.service.chat import ChatService
from tests.test_chat_history import HistoryMysqlCase


class ToolCallPersistenceTests(HistoryMysqlCase):
    async def asyncSetUp(self):
        await super().asyncSetUp()
        ddl = (Path(__file__).resolve().parents[2] / "sql/migrations/20261026_tool_call.sql").read_text(encoding="utf-8")
        async with self.engine.begin() as connection:
            await connection.execute(text(ddl))
            await connection.execute(text(ddl))  # 升级脚本可重复运行。
        self.tools = ToolCallRepository(self.engine)
        self.when = datetime(2026, 10, 26, 0, 0)

    def values(self, index, **overrides):
        return dict(attempt_id=f"{index:032x}", logical_call_id=f"logical-{index}",
            turn_id="a" * 32, user_id=23, session_id="s", agent="chat", tool_name="queryDayDetail",
            read_only=True, attempt_no=1, timeout_ms=5000, started_at=self.when,
            status="running", outcome="pending", **overrides)

    async def test_stats_use_exact_nearest_rank_and_exclude_other_users_and_running_calls(self):
        for index in range(1, 21):
            await self.tools.start(self.values(index))
            status = "success" if index <= 18 else "error" if index == 19 else "timeout"
            await self.tools.finish(f"{index:032x}", dict(status=status,
                outcome="success" if status == "success" else "failed", elapsed_ms=float(index)))
        row = self.values(21)
        row.update(tool_name="queryTodoList", turn_id="b" * 32)
        await self.tools.start(row)
        await self.tools.finish(row["attempt_id"], dict(status="success", outcome="success", elapsed_ms=100.0))
        row = self.values(22)
        row.update(tool_name="createTodo", read_only=False, outcome="uncertain", operation_key="c" * 64)
        await self.tools.start(row)
        row = self.values(23)
        row.update(user_id=99)
        await self.tools.start(row)
        await self.tools.finish(row["attempt_id"], dict(status="success", outcome="success", elapsed_ms=9999.0))
        stats = await self.tools.stats(23, self.when, self.when + timedelta(days=1))
        self.assertEqual((22, 1, 1), (stats["count"], stats["running"], stats["uncertain"]))
        self.assertEqual(21, stats["completed"]["count"])
        self.assertEqual(20, stats["completed"]["p95_ms"])
        self.assertAlmostEqual(310 / 21, stats["completed"]["avg_ms"])
        self.assertAlmostEqual(19 / 21, stats["completed"]["success_rate"])
        day = next(tool for tool in stats["tools"] if tool["tool_name"] == "queryDayDetail")
        self.assertEqual((10.5, 19, .9), (day["avg_ms"], day["p95_ms"], day["success_rate"]))
        self.assertEqual((2, 11, 21, 22), tuple(stats["per_turn"][key]
            for key in ("turns_with_calls", "avg_calls", "max_calls", "logical_count")))
        empty = await self.tools.stats(23, self.when - timedelta(days=1), self.when)
        self.assertEqual(0, empty["count"])
        self.assertIsNone(empty["completed"]["p95_ms"])
        self.assertIsNone(empty["completed"]["success_rate"])
        one_turn = await self.tools.stats(23, self.when, self.when + timedelta(days=1), "s", "b" * 32)
        self.assertEqual(1, one_turn["count"])
        self.assertEqual(1, one_turn["per_turn"]["max_calls"])

    async def test_unique_operation_claim_survives_concurrent_requests(self):
        first, second = self.values(1), self.values(2)
        for row in (first, second):
            row.update(read_only=False, outcome="uncertain", operation_key="c" * 64)
        results = await asyncio.gather(self.tools.start(first), self.tools.start(second), return_exceptions=True)
        self.assertEqual(1, sum(result is None for result in results))
        self.assertEqual(1, sum(isinstance(result, BusinessException) for result in results))

    async def test_paging_and_finish_are_scoped_and_terminal_is_written_once(self):
        for index in range(1, 5):
            row = self.values(index)
            row.update(user_id=99 if index == 4 else 23, session_id="other" if index == 3 else "s")
            await self.tools.start(row)
        rows, more = await self.tools.list_calls(23, "s", None, 1)
        self.assertTrue(more)
        self.assertEqual(2, rows[0].id)
        older, more = await self.tools.list_calls(23, "s", rows[0].id, 1)
        self.assertFalse(more)
        self.assertEqual(1, older[0].id)
        await self.tools.finish(older[0].attempt_id, dict(status="success", outcome="success", elapsed_ms=2.5))
        with self.assertRaises(RuntimeError):
            await self.tools.finish(older[0].attempt_id, dict(status="error", outcome="failed", elapsed_ms=3.5))
        async with self.tools.sessions() as session:
            row = await session.scalar(select(ToolCall).where(ToolCall.id == older[0].id))
            self.assertEqual("success", row.status)
            self.assertIsNotNone(row.finished_at)
        with patch("app.api.routers.tool_call_router.ToolCallRepository", return_value=self.tools):
            response = await self.client.get("/chat/tool-calls?sessionId=s")
            self.assertEqual(200, response.status_code)
            timestamp = datetime.fromisoformat(response.json()["data"]["items"][0]["started_at"])
            self.assertEqual(timedelta(0), timestamp.utcoffset())
            response = await self.client.get("/chat/tool-stats", params={
                "start": "2026-10-26T00:00:00Z", "end": "2026-10-27T00:00:00Z", "sessionId": "s"})
            self.assertEqual(200, response.status_code)
            self.assertEqual(2, response.json()["data"]["count"])
            self.assertEqual(2.5, response.json()["data"]["completed"]["p95_ms"])

    async def test_timed_out_write_keeps_persisted_claim_and_blocks_next_confirmation(self):
        flow = FlowStateRepository(self.engine)
        class Mcp:
            async def __aenter__(self):
                return self

            async def __aexit__(self, *_):
                pass

            async def list_tools(self, token):
                return [McpToolDefinition(name="deleteTodo", inputSchema={},
                    metadata=McpToolMetadata(readOnly=False, timeoutMs=5000))]

            call_tool = AsyncMock(side_effect=McpClientError("JAVA_TIMEOUT", "timeout", retryable=True))

        mcp = Mcp()
        model = SimpleNamespace(chat=AsyncMock(return_value=AssistantMessage(tool_calls=[
            ModelToolCall(id="write-1", function=ToolFunction(name="deleteTodo", arguments='{"todoId":3}'))])))
        route = SimpleNamespace(route=AsyncMock(side_effect=[
            RouteDecision(signal=UserSignal.NEW_EXECUTE, task="删除待办3"),
            RouteDecision(signal=UserSignal.CONFIRM)]))
        graph = ConversationGraph({AgentType.EXECUTOR: ExecuteNode(model, lambda: mcp)},
            user_id=23, session_id="s", chat_service=ChatService(flow), route_agent=route,
            summary_node=SummaryNode(service=SimpleNamespace(snapshot=AsyncMock(return_value=None))),
            tool_repository_factory=lambda: self.tools)
        await graph.run_turn("删除待办3", "token")
        with self.assertRaises(BusinessException) as caught:
            await graph.run_turn("确认", "token")
        self.assertEqual(ErrorCode.WRITE_RESULT_UNCERTAIN.code, caught.exception.code)
        self.assertEqual(1, (await flow.get(23, "s")).processing)
        with self.assertRaises(BusinessException):
            await graph.run_turn("确认", "token")
        mcp.call_tool.assert_awaited_once()
        rows, more = await self.tools.list_calls(23, "s", None, 30)
        self.assertFalse(more)
        self.assertEqual(1, len(rows))
        self.assertEqual(("timeout", "uncertain"), (rows[0].status, rows[0].outcome))
