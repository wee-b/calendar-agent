"""图片交互始终绑定当前草稿，修改外观时保留规划正文。"""

from app.helper.image_client import ImageClient
from app.repository.plan_draft import PlanDraftRepository
from app.schemas.statemachine.flow import AgentTurnResult, PendingTask


class ImageNode:
    def __init__(self, client=None, drafts=None):
        self.client = client if client is not None else ImageClient()
        self._drafts = drafts

    @property
    def drafts(self):
        if self._drafts is None:
            self._drafts = PlanDraftRepository()
        return self._drafts

    async def __call__(self, state, context) -> AgentTurnResult:
        pending = state["pending"]
        draft = await self.drafts.find_pending(state["user_id"], state["session_id"], pending.draft_id)
        if draft is None:
            return AgentTurnResult(reply="当前没有可用的规划草稿，请先生成或重新制定规划。", completed=False,
                                   pending=pending, dispatch_type="PENDING_UNKNOWN")
        instruction = (pending.image_instruction or "") + "\n用户补充/修改：" + state["message"]
        context.use_model("image", self.client)
        url = await self.client.generate(draft.plan_json, instruction)
        return AgentTurnResult(
            reply=f"已生成规划示意图：\n\n![规划示意图]({url})\n\n可以继续修改图片，或说“同步到日历”同步原规划。",
            completed=True, pending=PendingTask(task=pending.task, draft_id=pending.draft_id,
                                               plan_preview=pending.plan_preview, image_instruction=instruction),
            dispatch_type="PLAN_IMAGE")
