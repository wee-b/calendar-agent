"""AI 草稿由 Python 管理；日历业务写入通过 Java MCP 完成。"""

from datetime import datetime

from sqlalchemy import BigInteger, DateTime, String, Text, func
from sqlalchemy.orm import Mapped, mapped_column

from app.models.ai_dialogue import Base


class PlanDraft(Base):
    __tablename__ = "yl_plan_draft"

    draft_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    session_id: Mapped[str] = mapped_column(String(64), nullable=False)
    goal: Mapped[str | None] = mapped_column(String(255))
    plan_json: Mapped[str] = mapped_column(Text, nullable=False)
    status: Mapped[str] = mapped_column(String(20), default="pending")
    source_message: Mapped[str | None] = mapped_column(String(1000))
    create_time: Mapped[datetime] = mapped_column(DateTime, server_default=func.current_timestamp())
    update_time: Mapped[datetime] = mapped_column(DateTime, server_default=func.current_timestamp(), onupdate=func.current_timestamp())
