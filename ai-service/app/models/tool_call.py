"""一次真实工具尝试；不保存凭证、参数或原始结果。"""

from datetime import datetime, timezone

from sqlalchemy import BigInteger, Boolean, DateTime, Double, Index, Integer, String, UniqueConstraint
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class ToolCall(Base):
    __tablename__ = "yl_tool_call"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    attempt_id: Mapped[str] = mapped_column(String(32), nullable=False)
    logical_call_id: Mapped[str] = mapped_column(String(64), nullable=False)
    turn_id: Mapped[str] = mapped_column(String(32), nullable=False)
    user_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    session_id: Mapped[str] = mapped_column(String(64), nullable=False)
    agent: Mapped[str] = mapped_column(String(64), nullable=False)
    tool_name: Mapped[str] = mapped_column(String(64), nullable=False)
    read_only: Mapped[bool] = mapped_column(Boolean, nullable=False)
    attempt_no: Mapped[int] = mapped_column(Integer, nullable=False)
    # 写操作去重键；只读 NULL。结合认领版本，不阻止未来合法的相同操作。
    operation_key: Mapped[str | None] = mapped_column(String(64))
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="running")
    outcome: Mapped[str] = mapped_column(String(16), nullable=False, default="pending")
    timeout_ms: Mapped[int] = mapped_column(Integer, nullable=False)
    elapsed_ms: Mapped[float | None] = mapped_column(Double)
    error_code: Mapped[str | None] = mapped_column(String(64))
    started_at: Mapped[datetime] = mapped_column(DateTime, nullable=False,
        default=lambda: datetime.now(timezone.utc).replace(tzinfo=None))
    finished_at: Mapped[datetime | None] = mapped_column(DateTime)

    __table_args__ = (
        UniqueConstraint("attempt_id", name="uk_tool_attempt"),
        UniqueConstraint("operation_key", name="uk_tool_operation"),
        Index("idx_tool_user_time", "user_id", "started_at"),
        Index("idx_tool_user_session", "user_id", "session_id", "id"),
        Index("idx_tool_turn", "turn_id"),
        Index("idx_tool_outcome", "read_only", "outcome", "started_at"),
    )
