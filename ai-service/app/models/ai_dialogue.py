"""Mapping for the existing Java-created AI dialogue table."""

from datetime import datetime

from sqlalchemy import BigInteger, DateTime, SmallInteger, String, Text, func, text
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class AiDialogue(Base):
    __tablename__ = "yl_ai_dialogue"

    dialogue_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    session_id: Mapped[str] = mapped_column(String(64), nullable=False)
    role: Mapped[str] = mapped_column(String(16), nullable=False)
    user_text: Mapped[str | None] = mapped_column(Text)
    ai_result: Mapped[str | None] = mapped_column(Text)
    ai_audio_url: Mapped[str | None] = mapped_column(String(500))
    response_time_ms: Mapped[int | None] = mapped_column(BigInteger)
    intent: Mapped[str | None] = mapped_column(String(50))
    execute_result: Mapped[str | None] = mapped_column(String(255))
    deleted_flag: Mapped[int] = mapped_column(SmallInteger, server_default=text("0"), nullable=False)
    create_time: Mapped[datetime] = mapped_column(DateTime, server_default=func.current_timestamp())
    update_time: Mapped[datetime] = mapped_column(DateTime, server_default=func.current_timestamp())
