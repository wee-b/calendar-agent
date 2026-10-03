"""摘要采用消息快照和提交时校验，避免生成期间变更的历史被旧结果覆盖。"""
from dataclasses import dataclass, field
from sqlalchemy import select, text
from app.db.session import get_session_factory, session_factory_for_engine
from app.models.ai_dialogue import AiDialogue
from app.models.ai_session import AiSession
from app.repository.memory import MemoryRepository


@dataclass(frozen=True)
class SummarySnapshot:
    previous_id: int
    previous_text: str
    previous_count: int
    rows: list
    retained_ids: tuple[int, ...] = ()
    original_count: int = 0
    last_message_id: int | None = None
    retained_rows: list = field(default_factory=list)


class ContextSummaryRepository:
    def __init__(self, engine=None):
        self.sessions = session_factory_for_engine(engine) if engine is not None else get_session_factory()

    @staticmethod
    async def latest(session, user_id, session_id):
        return (await session.execute(text(
            "SELECT * FROM yl_chat_context_summary WHERE user_id=:u AND session_id=:s "
            "AND status='active' ORDER BY last_dialogue_id DESC, summary_id DESC LIMIT 1"
        ), {"u": user_id, "s": session_id})).mappings().first()

    async def snapshot(self, user_id, session_id, settings):
        async with self.sessions() as session:
            owner = await session.scalar(select(AiSession).where(
                AiSession.user_id == user_id, AiSession.session_id == session_id))
            if owner is None or owner.deleted_flag or owner.message_count < settings.summary_trigger_messages:
                return None
            previous = await self.latest(session, user_id, session_id)
            boundary = previous["last_dialogue_id"] if previous else 0
            rows = list((await session.scalars(select(AiDialogue).where(
                AiDialogue.user_id == user_id, AiDialogue.session_id == session_id,
                AiDialogue.deleted_flag == 0, AiDialogue.dialogue_id > boundary
            ).order_by(AiDialogue.dialogue_id))).all())
            if len(rows) < settings.summary_trigger_messages:
                return None
            retained = rows[-settings.summary_retain_messages:]
            return SummarySnapshot(previous["summary_id"] if previous else 0,
                                   previous["summary_text"] if previous else "",
                                   previous["message_count"] if previous else 0,
                                   rows[:-settings.summary_retain_messages],
                                   tuple(row.dialogue_id for row in retained),
                                   owner.message_count, owner.last_message_id, retained)

    async def save(self, user_id, session_id, snapshot, summary_text, preferences=()):
        async with self.sessions() as session, session.begin():
            # 与撤回/删除锁同一会话行；生成摘要期间不持有数据库锁。
            owner = await session.scalar(select(AiSession).where(
                AiSession.user_id == user_id, AiSession.session_id == session_id).with_for_update())
            if owner is None or owner.deleted_flag:
                return False
            if owner.message_count != snapshot.original_count or owner.last_message_id != snapshot.last_message_id:
                return False
            previous = await self.latest(session, user_id, session_id)
            if (previous["summary_id"] if previous else 0) != snapshot.previous_id:
                return False
            ids = [row.dialogue_id for row in snapshot.rows]
            all_ids = ids + list(snapshot.retained_ids)
            valid = list((await session.scalars(select(AiDialogue.dialogue_id).where(
                AiDialogue.user_id == user_id, AiDialogue.session_id == session_id,
                AiDialogue.dialogue_id.in_(all_ids), AiDialogue.deleted_flag == 0
            ).order_by(AiDialogue.dialogue_id))).all())
            if not ids or valid != all_ids:
                return False
            params = {"u": user_id, "s": session_id}
            await session.execute(text("UPDATE yl_chat_context_summary SET status='archived' "
                "WHERE user_id=:u AND session_id=:s AND status='active'"), params)
            await session.execute(text(
                "INSERT INTO yl_chat_context_summary "
                "(user_id,session_id,last_dialogue_id,message_count,summary_text,status) "
                "VALUES (:u,:s,:last,:count,:content,'active')"),
                {**params, "last": ids[-1], "count": snapshot.previous_count + len(ids), "content": summary_text})
            # 摘要、偏好和上下文计数原子提交；原消息保留供历史分页查询。
            for source_id, change in sorted(preferences, key=lambda item: item[0]):
                await MemoryRepository.apply(session, user_id, source_id, [change])
            owner.message_count = len(snapshot.retained_ids)
            return True
