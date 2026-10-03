"""会话元数据；与消息表共用 metadata，流程状态仍由 agent_flow_state 管理。"""

from datetime import datetime

from sqlalchemy import BigInteger, DateTime, Index, Integer, SmallInteger, String, func, text
from sqlalchemy.orm import Mapped, mapped_column

from app.models.ai_dialogue import Base


class AiSession(Base):
    __tablename__ = "yl_ai_session"
    __table_args__ = (
        Index("uk_user_session", "user_id", "session_id", unique=True),
        Index("idx_user_recent", "user_id", "deleted_flag", "last_message_time", "id"),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    session_id: Mapped[str] = mapped_column(String(64), nullable=False)
    title: Mapped[str | None] = mapped_column(String(128))
    last_message_id: Mapped[int | None] = mapped_column(BigInteger)
    last_message_time: Mapped[datetime | None] = mapped_column(DateTime)
    # 未压缩的有效上下文消息数；不含历史摘要，用户/助手消息分别计数。
    message_count: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("0"))
    deleted_flag: Mapped[int] = mapped_column(SmallInteger, nullable=False, server_default=text("0"))
    create_time: Mapped[datetime] = mapped_column(DateTime, server_default=func.current_timestamp())
    update_time: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.current_timestamp(), onupdate=func.current_timestamp()
    )
