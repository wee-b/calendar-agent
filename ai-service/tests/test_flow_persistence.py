"""真实 MySQL 验证流程认领、原子提交和草稿隔离；仅使用随机测试库。"""

import asyncio
from unittest.mock import patch

from sqlalchemy import func, select

from app.models.ai_dialogue import AiDialogue
from app.models.ai_session import AiSession
from app.repository.chat import ChatRepository
from app.repository.flow_state import FlowStateRepository
from app.repository.plan_draft import PlanDraftRepository
from app.schemas.plan import parse_plan
from app.schemas.chat.timeline import AgentStep
from app.schemas.chat.history import DocumentReference
from app.schemas.statemachine.flow import ConversationStage, PendingTask
from app.schemas.statemachine.transitions import AgentType
from tests.test_chat_history import HistoryMysqlCase
from tests.test_migrated_nodes import plan_json


class FlowPersistenceTests(HistoryMysqlCase):
    async def test_concurrent_claim_and_complete_commit_one_round(self):
        repo = FlowStateRepository(self.engine)
        first = await repo.get_or_create(23, "s")
        second = await repo.get(23, "s")
        claimed = await asyncio.gather(repo.claim(first), repo.claim(second))
        self.assertEqual(1, sum(claimed))
        owner = first if claimed[0] else second
        await repo.complete(owner, ConversationStage.PLAN, PendingTask(task="学习", draft_id=7),
                            AgentType.PLANNER, message="学习计划", reply="规划正文", elapsed_ms=5,
                            dispatch_type="PLAN",
                            document_references=[DocumentReference(fileId=12, fileName="学习资料.pdf")],
                            agent_steps=[AgentStep(id=1, kind="agent", label="制定规划",
                                                   narration="先学习基础，再做练习。",
                                                   status="success", elapsedMs=2, round=1),
                                         AgentStep(id=2, kind="tool", label="查询日程",
                                                   resultSummary="2026-10-04：2 项待办。",
                                                   status="success", elapsedMs=3, round=1)])
        saved = await repo.get(23, "s")
        self.assertEqual(("PLAN", 7, 0), (saved.stage, saved.pending_draft_id, saved.processing))
        async with repo.sessions() as session:
            messages = (await session.scalars(select(AiDialogue).order_by(AiDialogue.dialogue_id))).all()
            self.assertEqual(["学习计划", "规划正文"], [m.content for m in messages])
            self.assertEqual([{"fileId": 12, "fileName": "学习资料.pdf"}], messages[0].document_references)
            self.assertIsNone(messages[1].document_references)
            self.assertEqual("制定规划", messages[1].agent_steps[0]["label"])
            self.assertEqual("PLAN", messages[1].dispatch_type)
            self.assertEqual("先学习基础，再做练习。", messages[1].agent_steps[0]["narration"])
            self.assertEqual("2026-10-04：2 项待办。", messages[1].agent_steps[1]["resultSummary"])
            self.assertEqual(2, (await session.scalar(select(AiSession))).message_count)
        history = await ChatRepository(self.engine).list_history(23, "s")
        self.assertEqual("制定规划", history[0][0].agent_steps[0]["label"])
        page = (await self.client.get("/chat/history?sessionId=s")).json()["data"]
        self.assertEqual([{"fileId": 12, "fileName": "学习资料.pdf"}], page["items"][0]["documentReferences"])
        self.assertEqual([], page["items"][1]["documentReferences"])
        self.assertEqual("制定规划", page["items"][-1]["agentSteps"][0]["label"])
        self.assertEqual("PLAN", page["items"][-1]["dispatchType"])
        self.assertEqual("先学习基础，再做练习。", page["items"][-1]["agentSteps"][0]["narration"])
        self.assertEqual("2026-10-04：2 项待办。", page["items"][-1]["agentSteps"][1]["resultSummary"])

    async def test_failed_message_append_rolls_back_stage_and_messages(self):
        repo = FlowStateRepository(self.engine)
        state = await repo.get_or_create(23, "s")
        self.assertTrue(await repo.claim(state))
        append = ChatRepository.append_locked

        async def fail_after_insert(*args):
            await append(*args)
            raise RuntimeError("模拟提交前故障")

        with patch.object(ChatRepository, "append_locked", side_effect=fail_after_insert):
            with self.assertRaises(RuntimeError):
                await repo.complete(state, ConversationStage.PLAN, PendingTask(draft_id=7), AgentType.PLANNER,
                                    message="计划", reply="正文")
        saved = await repo.get(23, "s")
        self.assertEqual(("CHAT", None, 1), (saved.stage, saved.pending_draft_id, saved.processing))
        async with repo.sessions() as session:
            self.assertEqual(0, await session.scalar(select(func.count()).select_from(AiDialogue)))
            self.assertEqual(0, (await session.scalar(select(AiSession))).message_count)
        await repo.release_claim(state)
        self.assertEqual(0, (await repo.get(23, "s")).processing)

    async def test_draft_requires_matching_user_session_id_and_pending_status(self):
        repo = PlanDraftRepository(self.engine)
        draft = await repo.save(23, "s", "学习", parse_plan(plan_json()))
        self.assertIsNotNone(await repo.find_pending(23, "s", draft.draft_id))
        for user, session, draft_id in ((24, "s", draft.draft_id), (23, "other", draft.draft_id), (23, "s", None)):
            self.assertIsNone(await repo.find_pending(user, session, draft_id))
        await repo.mark_synced(23, "s", draft.draft_id)
        self.assertIsNone(await repo.find_pending(23, "s", draft.draft_id))
        with self.assertRaises(RuntimeError):
            await repo.mark_synced(23, "s", draft.draft_id)
