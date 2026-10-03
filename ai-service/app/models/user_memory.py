"""沿用既有长期记忆表，保存用户偏好与目标。"""
from datetime import datetime
from decimal import Decimal
from sqlalchemy import BigInteger, DateTime, Numeric, String, func
from sqlalchemy.orm import Mapped, mapped_column
from app.models.ai_dialogue import Base


class UserMemory(Base):
    __tablename__ = "yl_user_memory"
    memory_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(BigInteger)
    memory_type: Mapped[str] = mapped_column(String(32))
    content: Mapped[str] = mapped_column(String(1000))
    normalized_key: Mapped[str | None] = mapped_column(String(128))
    source: Mapped[str] = mapped_column(String(32), default="chat")
    source_id: Mapped[int | None] = mapped_column(BigInteger)
    confidence: Mapped[Decimal] = mapped_column(Numeric(4, 3), default=Decimal("0.9"))
    status: Mapped[str] = mapped_column(String(20), default="active")
    last_used_time: Mapped[datetime | None] = mapped_column(DateTime)
    expire_time: Mapped[datetime | None] = mapped_column(DateTime)
    create_time: Mapped[datetime] = mapped_column(DateTime, server_default=func.current_timestamp())
    update_time: Mapped[datetime] = mapped_column(DateTime, server_default=func.current_timestamp(),
                                                onupdate=func.current_timestamp())
