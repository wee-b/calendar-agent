"""摘要数据读写边界。

Service 只能封装查询和事务保存；禁止声明模型、抽取偏好、决定执行顺序，
也不能创建后台任务或队列。触发条件与模型任务由 ConversationGraph 的节点编排。
"""
from app.repository.context_summary import ContextSummaryRepository


class ContextSummaryService:
    def __init__(self, repository=None):
        self.repository = repository

    def _repository(self):
        if self.repository is None:
            self.repository = ContextSummaryRepository()
        return self.repository

    async def snapshot(self, user_id, session_id, settings):
        return await self._repository().snapshot(user_id, session_id, settings)

    async def save(self, user_id, session_id, snapshot, summary, preferences):
        return await self._repository().save(user_id, session_id, snapshot, summary, preferences)
