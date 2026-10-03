"""记忆写入、冲突归档和用户隔离；模型调用由节点负责。"""
from datetime import timedelta
from decimal import Decimal
from sqlalchemy import select, update, or_, func
from app.db.session import get_session_factory, session_factory_for_engine
from app.models.user_memory import UserMemory


class MemoryRepository:
    def __init__(self, engine=None):
        self.sessions = session_factory_for_engine(engine) if engine is not None else get_session_factory()

    @staticmethod
    async def apply(session, user_id, source_id, changes):
        if not changes:
            return
        now = await session.scalar(select(func.current_timestamp()))
        for change in changes:
            existing = list((await session.scalars(select(UserMemory).where(
                UserMemory.user_id == user_id, UserMemory.normalized_key == change.key
            ).order_by(UserMemory.memory_id.desc()).with_for_update())).all())
            # 跨会话提交顺序可能与 dialogue_id 顺序不同，旧输入不能覆盖新偏好。
            if any(row.source == "chat" and (row.source_id or 0) >= source_id for row in existing):
                continue
            active = [row for row in existing if row.status == "active"]
            if not change.forget and len(active) == 1 and active[0].content == change.content:
                active[0].source_id = source_id
                if change.memory_type == "LONG_TERM_GOAL":
                    active[0].expire_time = now + timedelta(days=90)
                continue
            for row in active:
                row.status = "archived"
            # 删除也写墓碑，防止更早提交的输入重新激活偏好。
            session.add(UserMemory(user_id=user_id, memory_type=change.memory_type,
                normalized_key=change.key, content=change.content, source="chat", source_id=source_id,
                status="deleted" if change.forget else "active", confidence=Decimal("0.9"),
                expire_time=now + timedelta(days=90) if change.memory_type == "LONG_TERM_GOAL" else None))
        await session.flush()

    async def list_active(self, user_id):
        async with self.sessions() as session:
            return list((await session.scalars(select(UserMemory).where(
                UserMemory.user_id == user_id, UserMemory.status == "active",
                UserMemory.source != "behavior", UserMemory.memory_type != "BEHAVIOR_PATTERN",
                or_(UserMemory.expire_time.is_(None), UserMemory.expire_time > func.current_timestamp())
            ).order_by(UserMemory.update_time.desc(), UserMemory.memory_id.desc()).limit(200))).all())

    async def delete(self, user_id, memory_id):
        async with self.sessions() as session, session.begin():
            result = await session.execute(update(UserMemory).where(
                UserMemory.user_id == user_id, UserMemory.memory_id == memory_id,
                UserMemory.status == "active").values(status="deleted"))
            return result.rowcount == 1
