"""Conversation persistence in the existing yl_ai_dialogue table."""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncEngine

from app.db.session import get_session_factory, session_factory_for_engine
from app.models.ai_dialogue import AiDialogue


class ChatRepository:
    def __init__(self, engine: AsyncEngine | None = None) -> None:
        self.sessions = session_factory_for_engine(engine) if engine else get_session_factory()

    async def recent_messages(self, user_id: int, session_id: str) -> list[dict[str, str]]:
        async with self.sessions() as session:
            rows = (await session.execute(
                select(AiDialogue.role, AiDialogue.user_text, AiDialogue.ai_result)
                .where(AiDialogue.user_id == user_id,
                       AiDialogue.session_id == session_id,
                       AiDialogue.deleted_flag == 0)
                .order_by(AiDialogue.dialogue_id.desc()).limit(20)
            )).all()
        messages = []
        for row in reversed(rows):
            content = row.user_text if row.role == "user" else row.ai_result
            if row.role in ("user", "assistant") and content:
                messages.append({"role": row.role, "content": content})
        return messages

    async def save_round(
        self, user_id: int, session_id: str, message: str, answer: str, elapsed_ms: int
    ) -> None:
        async with self.sessions() as session:
            async with session.begin():
                session.add(AiDialogue(user_id=user_id, session_id=session_id,
                                       role="user", user_text=message))
                session.add(AiDialogue(user_id=user_id, session_id=session_id,
                                       role="assistant", ai_result=answer,
                                       response_time_ms=elapsed_ms))
