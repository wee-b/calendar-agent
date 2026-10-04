"""一行一条消息，角色与正文分开存储；会话元数据见 ai_session。"""

from datetime import datetime

from sqlalchemy import JSON, BigInteger, DateTime, Index, SmallInteger, String, Text, func, text
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class AiDialogue(Base):
    __tablename__ = "yl_ai_dialogue"
    __table_args__ = (
        Index("idx_history", "user_id", "session_id", "deleted_flag", "dialogue_id"),
    )

    dialogue_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    session_id: Mapped[str] = mapped_column(String(64), nullable=False)
    role: Mapped[str] = mapped_column(String(16), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    ai_audio_url: Mapped[str | None] = mapped_column(String(500))
    response_time_ms: Mapped[int | None] = mapped_column(BigInteger)
    agent_steps: Mapped[list | None] = mapped_column(JSON, nullable=True)
    deleted_flag: Mapped[int] = mapped_column(SmallInteger, server_default=text("0"), nullable=False)
    create_time: Mapped[datetime] = mapped_column(DateTime, server_default=func.current_timestamp())
    update_time: Mapped[datetime] = mapped_column(DateTime, server_default=func.current_timestamp())
