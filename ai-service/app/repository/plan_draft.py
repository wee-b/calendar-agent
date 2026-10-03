"""只能按用户、会话和显式 draft_id 读取规划，禁止回退到最新草稿。"""

from sqlalchemy import select, update

from app.db.session import get_session_factory, session_factory_for_engine
from app.models.plan_draft import PlanDraft
from app.schemas.plan import Plan


class PlanDraftRepository:
    def __init__(self, engine=None):
        self.sessions = session_factory_for_engine(engine) if engine is not None else get_session_factory()

    async def save(self, user_id: int, session_id: str, requirement: str, plan: Plan) -> PlanDraft:
        draft = PlanDraft(user_id=user_id, session_id=session_id, goal=plan.goal,
                          plan_json=plan.model_dump_json(), status="pending",
                          source_message=requirement[:1000])
        async with self.sessions() as session, session.begin():
            session.add(draft)
            await session.flush()
        return draft

    async def find_pending(self, user_id: int, session_id: str, draft_id: int | None) -> PlanDraft | None:
        if draft_id is None:
            return None
        async with self.sessions() as session:
            return await session.scalar(select(PlanDraft).where(
                PlanDraft.user_id == user_id, PlanDraft.session_id == session_id,
                PlanDraft.draft_id == draft_id, PlanDraft.status == "pending",
            ))

    async def mark_synced(self, user_id: int, session_id: str, draft_id: int) -> None:
        async with self.sessions() as session, session.begin():
            result = await session.execute(update(PlanDraft).where(
                PlanDraft.user_id == user_id, PlanDraft.session_id == session_id,
                PlanDraft.draft_id == draft_id, PlanDraft.status == "pending",
            ).values(status="synced"))
            if result.rowcount != 1:
                raise RuntimeError("规划草稿状态已变化，请核实日历同步结果")
