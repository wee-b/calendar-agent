"""前端规划状态摘要、会话恢复及用户隔离契约。"""

import json
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import httpx

from app.all_graph.conversation_graph import ConversationResult
from app.api.conversation_transport import ConversationTransport
from app.main import app
from app.schemas.chat import ChatRequest
from app.schemas.chat.planning_state import PlanningUiState
from app.schemas.plan.preparation import PlanningPreparation
from app.schemas.statemachine.flow import ConversationStage, PendingTask, UserSignal
from app.schemas.statemachine.transitions import AgentType
from tests.planning_fakes import day_range


def pending_questions():
    return PendingTask(task="英语复习", draft_id=8, plan_preview="旧草稿",
        planning=PlanningPreparation(date_range=day_range(), document_ids=[42],
            questions=["每天能投入多久？"], references="private document passages",
            memories=[{"memory_type": "PREFERENCE_TIME", "content": "private preference text"}]))


class PlanningUiStateTests(unittest.IsolatedAsyncioTestCase):
    def test_questions_take_priority_over_an_older_draft_and_only_public_fields_are_exposed(self):
        pending = pending_questions()
        summary = PlanningUiState.from_pending(pending, "PLAN")
        self.assertEqual("awaiting_answers", summary.status)
        self.assertEqual(["每天能投入多久？"], summary.questions)
        self.assertEqual(day_range(), summary.dateRange)
        self.assertEqual(1, summary.referenceCount)
        self.assertEqual(16, len(summary.questionSetId))
        encoded = summary.model_dump_json()
        for private in ("private document passages", "private preference text", "旧草稿", "draft_id", "document_ids"):
            self.assertNotIn(private, encoded)
        self.assertEqual(summary.questionSetId, PlanningUiState.from_pending(pending.model_copy(deep=True), "PLAN").questionSetId)
        pending.task += "\n用户已经补充了条件"
        self.assertNotEqual(summary.questionSetId, PlanningUiState.from_pending(pending, "PLAN").questionSetId)

    def test_only_active_plan_or_image_stages_expose_draft_actions(self):
        pending = PendingTask(draft_id=8)
        self.assertEqual("draft_ready", PlanningUiState.from_pending(pending, "PLAN").status)
        self.assertEqual("draft_ready", PlanningUiState.from_pending(pending, "IMAGE").status)
        for stage in ("CHAT", "EXECUTE"):
            self.assertEqual("none", PlanningUiState.from_pending(pending_questions(), stage).status)

    async def test_result_transport_contains_planning_state_even_after_a_temporary_query(self):
        result = ConversationResult(reply="日程已查询。", signal=UserSignal.NEW_QUERY, agent=AgentType.CHAT,
            stage=ConversationStage.PLAN, pending=pending_questions(), dispatch_type="QUERY", rounds=1, elapsed_ms=10)
        graph = SimpleNamespace(run_turn=AsyncMock(return_value=result))
        response = await ConversationTransport(lambda **_: graph).reply(
            23, ChatRequest(sessionId="s", message="查一下今天的安排"), "token")
        self.assertEqual("QUERY", response.dispatchType)
        self.assertEqual("awaiting_answers", response.planning.status)
        self.assertEqual(["每天能投入多久？"], response.planning.questions)

    async def test_state_endpoint_requires_authentication(self):
        with patch("app.api.routers.chat_router.ChatService") as service:
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                self.assertEqual(401, (await client.get("/chat/state?sessionId=s")).status_code)
            service.assert_not_called()

    async def test_state_endpoint_restores_only_authenticated_user_state_without_creating_a_session(self):
        verifier = SimpleNamespace(verify=AsyncMock(return_value=23))
        service = SimpleNamespace(get_flow_state=AsyncMock(return_value=SimpleNamespace(stage="PLAN")),
            get_or_create_flow_state=AsyncMock(), resolve_flow_stage=lambda _: ConversationStage.PLAN,
            flow_pending=lambda _: pending_questions())
        with patch("app.core.middleware.auth.RedisTokenVerifier", return_value=verifier), \
             patch("app.api.routers.chat_router.ChatService", return_value=service):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                response = await client.get("/chat/state?sessionId=s&userId=999", headers={"yvli-token": "valid"})
        self.assertEqual(200, response.status_code)
        payload = response.json()["data"]
        self.assertEqual("s", payload["sessionId"])
        self.assertEqual("PLAN", payload["flowStage"])
        self.assertEqual("awaiting_answers", payload["planning"]["status"])
        self.assertNotIn("private", json.dumps(payload))
        service.get_flow_state.assert_awaited_once_with(23, "s")
        service.get_or_create_flow_state.assert_not_awaited()

    async def test_unstarted_session_returns_empty_state_and_invalid_ids_are_rejected(self):
        verifier = SimpleNamespace(verify=AsyncMock(return_value=23))
        service = SimpleNamespace(get_flow_state=AsyncMock(return_value=None), get_or_create_flow_state=AsyncMock())
        with patch("app.core.middleware.auth.RedisTokenVerifier", return_value=verifier), \
             patch("app.api.routers.chat_router.ChatService", return_value=service):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test",
                                         headers={"yvli-token": "valid"}) as client:
                for query in ("", "?sessionId=", "?sessionId=%20"):
                    self.assertEqual(422, (await client.get("/chat/state" + query)).status_code)
                service.get_flow_state.assert_not_awaited()
                payload = (await client.get("/chat/state?sessionId=new")).json()["data"]
        self.assertEqual("none", payload["planning"]["status"])
        self.assertEqual("CHAT", payload["flowStage"])
        service.get_flow_state.assert_awaited_once_with(23, "new")
        service.get_or_create_flow_state.assert_not_awaited()
