"""规划数据操作：封装记忆查询与草稿保存，不负责模型调用。"""

from app.models.plan_draft import PlanDraft
from app.repository.plan_draft import PlanDraftRepository
from app.repository.planning_memory import PlanningMemoryRepository
from app.schemas.plan import Plan


class PlanningService:
    def __init__(self, drafts=None, memory=None):
        self._drafts = drafts
        self.memory = memory if memory is not None else PlanningMemoryRepository()

    @property
    def drafts(self):
        if self._drafts is None:
            self._drafts = PlanDraftRepository()
        return self._drafts

    async def memories(self, user_id: int, requirement: str) -> list[dict]:
        return await self.memory.relevant(user_id, requirement)

    async def save_draft(self, user_id: int, session_id: str, requirement: str, plan: Plan) -> PlanDraft:
        return await self.drafts.save(user_id, session_id, requirement, plan)
