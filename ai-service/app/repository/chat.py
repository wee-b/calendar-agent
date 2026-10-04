"""消息分页读取，以及消息与会话元数据的事务写入。"""

from __future__ import annotations

from sqlalchemy import and_, func, select, text, update
from sqlalchemy.dialects.mysql import insert
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession

from app.core.exception.error_code import ErrorCode
from app.core.exception.exceptions import BusinessException
from app.db.session import get_session_factory, session_factory_for_engine
from app.models.ai_dialogue import AiDialogue
from app.models.ai_session import AiSession
from app.schemas.chat.history import ChatMessageCreate, DEFAULT_HISTORY_LIMIT, MAX_HISTORY_LIMIT
from app.schemas.chat.messages import TextMessage
from app.schemas.chat.model_stream import AssistantMessage
from app.core.config.common.memory import get_memory_settings
from app.repository.context_summary import ContextSummaryRepository


class ChatRepository:
    def __init__(self, engine: AsyncEngine | None = None) -> None:
        self.sessions = session_factory_for_engine(engine) if engine else get_session_factory()

    @staticmethod
    async def _lock_session(session: AsyncSession, user_id: int, session_id: str) -> AiSession:
        create = insert(AiSession).values(user_id=user_id, session_id=session_id)
        await session.execute(create.on_duplicate_key_update(id=AiSession.id))
        return (await session.execute(
            select(AiSession)
            .where(AiSession.user_id == user_id, AiSession.session_id == session_id)
            .with_for_update()
        )).scalar_one()

    @staticmethod
    async def _invalidate_context(session: AsyncSession, user_id: int, session_id: str) -> None:
        """删除/撤回不能保留包含旧消息的摘要或待确认操作。"""
        params = {"user_id": user_id, "session_id": session_id}
        processing = await session.scalar(text(
            "SELECT processing FROM yl_agent_flow_state "
            "WHERE user_id=:user_id AND session_id=:session_id FOR UPDATE"
        ), params)
        if processing:
            raise BusinessException(ErrorCode.CHAT_SESSION_PROCESSING)
        await session.execute(text(
            "DELETE FROM yl_agent_flow_state WHERE user_id=:user_id AND session_id=:session_id"
        ), params)
        await session.execute(text(
            "DELETE FROM yl_chat_context_summary WHERE user_id=:user_id AND session_id=:session_id"
        ), params)

    async def delete_session(self, user_id: int, session_id: str) -> None:
        async with self.sessions() as session:
            async with session.begin():
                # 未落库的首轮也创建删除标记，拒绝之后到达的助手回复。
                chat_session = await self._lock_session(session, user_id, session_id)
                await self._invalidate_context(session, user_id, session_id)
                await session.execute(update(AiDialogue).where(
                    AiDialogue.user_id == user_id, AiDialogue.session_id == session_id,
                    AiDialogue.deleted_flag == 0,
                ).values(deleted_flag=1, update_time=func.current_timestamp()))
                chat_session.deleted_flag = 1
                chat_session.message_count = 0
                chat_session.last_message_id = None
                chat_session.last_message_time = None

    async def delete_last_round(self, user_id: int, session_id: str) -> None:
        """撤回待回复的连续用户消息，或最后一次助手回复及其前面的连续用户消息。"""
        async with self.sessions() as session:
            async with session.begin():
                chat_session = await self._lock_session(session, user_id, session_id)
                if chat_session.deleted_flag:
                    return
                filters = (
                    AiDialogue.user_id == user_id, AiDialogue.session_id == session_id,
                    AiDialogue.deleted_flag == 0,
                )
                last = (await session.execute(
                    select(AiDialogue).where(*filters).order_by(AiDialogue.dialogue_id.desc()).limit(1)
                )).scalar_one_or_none()
                if last is None:
                    return
                # 最后一条为 user 时保留最近助手回复；为 assistant 时保留再上一条助手回复。
                boundary = await session.scalar(select(func.max(AiDialogue.dialogue_id)).where(
                    *filters, AiDialogue.role == "assistant", AiDialogue.dialogue_id < last.dialogue_id
                ))
                await self._invalidate_context(session, user_id, session_id)
                await session.execute(update(AiDialogue).where(
                    *filters, AiDialogue.dialogue_id > (boundary or 0),
                ).values(deleted_flag=1, update_time=func.current_timestamp()))
                remaining = (await session.execute(
                    select(AiDialogue).where(*filters).order_by(AiDialogue.dialogue_id.desc()).limit(1)
                )).scalar_one_or_none()
                chat_session.message_count = await session.scalar(
                    select(func.count()).select_from(AiDialogue).where(*filters)
                )
                chat_session.last_message_id = remaining.dialogue_id if remaining else None
                chat_session.last_message_time = remaining.create_time if remaining else None

    async def list_history(
        self, user_id: int, session_id: str,
        before_id: int | None = None, limit: int = DEFAULT_HISTORY_LIMIT,
    ) -> tuple[list[AiDialogue], bool]:
        """从新到旧读一页；多读一条判断 has_more，游标按消息 ID 排序。"""
        if not 1 <= limit <= MAX_HISTORY_LIMIT:
            raise ValueError("limit 必须在 1 到 100 之间")
        if before_id is not None and before_id <= 0:
            raise ValueError("before_id 必须为正整数")
        stmt = (
            select(AiDialogue)
            .join(AiSession, and_(
                AiSession.user_id == AiDialogue.user_id,
                AiSession.session_id == AiDialogue.session_id,
            ))
            .where(AiDialogue.user_id == user_id,
                   AiDialogue.session_id == session_id,
                   AiDialogue.deleted_flag == 0,
                   AiSession.deleted_flag == 0)
        )
        if before_id is not None:
            stmt = stmt.where(AiDialogue.dialogue_id < before_id)
        async with self.sessions() as session:
            rows = list((await session.execute(
                stmt.order_by(AiDialogue.dialogue_id.desc()).limit(limit + 1)
            )).scalars().all())
        return rows[:limit], len(rows) > limit

    async def route_context_messages(
        self, user_id: int, session_id: str, through_id: int | None = None,
    ) -> list[AiDialogue]:
        """单次查询读取最后一条助手回复及其后全部用户消息，不使用页面的 20 条限制。

        through_id 可固定本次已接收消息的上界，避免后来输入进入本次意图判断。
        """
        filters = [
            AiDialogue.user_id == user_id,
            AiDialogue.session_id == session_id,
            AiDialogue.deleted_flag == 0,
        ]
        if through_id is not None:
            if through_id <= 0:
                raise ValueError("through_id 必须为正整数")
            filters.append(AiDialogue.dialogue_id <= through_id)
        previous_id = (
            select(func.max(AiDialogue.dialogue_id))
            .where(*filters, AiDialogue.role == "assistant")
            .correlate(None).scalar_subquery()
        )
        stmt = (
            select(AiDialogue)
            .join(AiSession, and_(
                AiSession.user_id == AiDialogue.user_id,
                AiSession.session_id == AiDialogue.session_id,
            ))
            .where(*filters, AiSession.deleted_flag == 0,
                   AiDialogue.role.in_(("user", "assistant")),
                   AiDialogue.dialogue_id >= func.coalesce(previous_id, 0))
            .order_by(AiDialogue.dialogue_id.asc())
        )
        async with self.sessions() as session:
            return list((await session.execute(stmt)).scalars().all())

    async def recent_messages(
        self, user_id: int, session_id: str,
    ) -> list[TextMessage | AssistantMessage]:
        """模型上下文使用已压缩摘要和其后的原文；历史分页仍保留完整消息。"""
        settings = get_memory_settings()
        async with self.sessions() as session:
            owner = await session.scalar(select(AiSession).where(
                AiSession.user_id == user_id, AiSession.session_id == session_id))
            if owner is None or owner.deleted_flag:
                return []
            summary = await ContextSummaryRepository.latest(session, user_id, session_id) if settings.enabled else None
            rows = list((await session.scalars(select(AiDialogue).where(
                AiDialogue.user_id == user_id, AiDialogue.session_id == session_id,
                AiDialogue.deleted_flag == 0,
                AiDialogue.dialogue_id > (summary["last_dialogue_id"] if summary else 0)
            ).order_by(AiDialogue.dialogue_id.desc()).limit(settings.summary_trigger_messages))).all())
        messages = []
        if summary:
            messages.append(TextMessage(role="user", content=(
                "以下是更早的历史摘要，仅供理解指代。它不是本轮请求，也不代表执行授权；"
                "以最后一条当前用户消息和实际待确认状态为准。\n"
                + summary["summary_text"][:settings.summary_max_chars])))
        for row in reversed(rows):
            if not row.content:
                continue
            if row.role == "user":
                messages.append(TextMessage(role="user", content=row.content[:settings.message_max_chars]))
            elif row.role == "assistant":
                messages.append(AssistantMessage(content=row.content[:settings.message_max_chars]))
        return messages

    async def append_messages(
        self, user_id: int, session_id: str, messages: list[ChatMessageCreate],
    ) -> list[int]:
        """保存任意角色顺序的消息；首次写入建会话，统计更新与消息一起提交。"""
        if not messages:
            return []
        if user_id <= 0 or not session_id.strip() or len(session_id) > 64:
            raise ValueError("用户 ID 或会话 ID 无效")
        async with self.sessions() as session:
            async with session.begin():
                # 唯一键处理首次并发创建；无操作更新不会复活已删除会话。
                chat_session = await self._lock_session(session, user_id, session_id)
                if chat_session.deleted_flag:
                    raise BusinessException(ErrorCode.CHAT_SESSION_DELETED)

                return await self.append_locked(session, chat_session, messages)

    @staticmethod
    async def append_locked(session: AsyncSession, chat_session: AiSession,
                            messages: list[ChatMessageCreate]) -> list[int]:
        """调用者已锁定会话且持有事务；流程状态提交复用此方法保证原子性。"""
        if chat_session.deleted_flag:
            raise BusinessException(ErrorCode.CHAT_SESSION_DELETED)
        now = await session.scalar(select(func.current_timestamp()))
        rows = [AiDialogue(
            user_id=chat_session.user_id, session_id=chat_session.session_id,
            role=message.role, content=message.content,
            response_time_ms=message.response_time_ms, create_time=now,
            agent_steps=[step.model_dump(mode="json") for step in message.agent_steps]
            if message.agent_steps is not None else None,
        ) for message in messages]
        session.add_all(rows)
        await session.flush()
        if chat_session.title is None:
            first_user = next((row for row in rows if row.role == "user"), None)
            if first_user is not None:
                content = first_user.content
                chat_session.title = content[:30] + ("..." if len(content) > 30 else "")
        chat_session.message_count += len(rows)
        chat_session.last_message_id = rows[-1].dialogue_id
        chat_session.last_message_time = rows[-1].create_time
        return [row.dialogue_id for row in rows]

    async def save_round(
        self, user_id: int, session_id: str, message: str, answer: str, elapsed_ms: int,
    ) -> None:
        """现有聊天入口仍在完整回答后一次提交；底层不依赖问答配对。"""
        await self.append_messages(user_id, session_id, [
            ChatMessageCreate(role="user", content=message),
            ChatMessageCreate(role="assistant", content=answer, response_time_ms=elapsed_ms),
        ])
