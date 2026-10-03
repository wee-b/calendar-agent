"""直接读取会话表，不扫描消息正文计算会话列表。"""

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncEngine

from app.db.session import get_session_factory, session_factory_for_engine
from app.models.ai_session import AiSession


class ChatSessionRepository:
    def __init__(self, engine: AsyncEngine | None = None) -> None:
        self.sessions = session_factory_for_engine(engine) if engine else get_session_factory()

    @staticmethod
    def _list_query(user_id: int):
        return (
            select(AiSession)
            .where(AiSession.user_id == user_id, AiSession.deleted_flag == 0,
                   AiSession.last_message_id.is_not(None))
            .order_by(AiSession.last_message_time.desc(), AiSession.id.desc())
        )

    async def list_sessions(self, user_id: int) -> list[AiSession]:
        async with self.sessions() as session:
            result = await session.execute(self._list_query(user_id))
            return list(result.scalars().all())

