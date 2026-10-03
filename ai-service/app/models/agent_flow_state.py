from sqlalchemy import BigInteger, String, Text, DateTime, Index
from sqlalchemy.dialects.mysql import MEDIUMTEXT, TINYINT
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column
from sqlalchemy.sql import func
from typing import Optional


class Base(DeclarativeBase):
    pass


class YlAgentFlowState(Base):
    __tablename__ = "yl_agent_flow_state"

    state_id: Mapped[int] = mapped_column(
        BigInteger,
        primary_key=True,
        autoincrement=True,
        comment="Agent流程状态ID"
    )
    user_id: Mapped[int] = mapped_column(BigInteger, nullable=False, comment="用户ID")
    session_id: Mapped[str] = mapped_column(String(64), nullable=False, comment="会话ID")
    current_agent: Mapped[str] = mapped_column(
        String(32),
        nullable=False,
        default="NONE",
        comment="当前执行Agent：NONE/对话Agent/规划Agent/执行Agent"
    )
    next_agent: Mapped[str] = mapped_column(
        String(32),
        nullable=False,
        default="NONE",
        comment="用户确认后将要执行的Agent"
    )
    stage: Mapped[str] = mapped_column(
        String(32),
        nullable=False,
        default="CHAT",
        comment="对话阶段：对话/规划/执行/生图"
    )
    pending_task: Mapped[Optional[str]] = mapped_column(
        Text,
        nullable=True,
        default=None,
        comment="待确认任务或优化后的指令"
    )
    pending_payload: Mapped[Optional[str]] = mapped_column(
        MEDIUMTEXT,
        nullable=True,
        default=None,
        comment="Agent输出内容，例如规划结果"
    )
    pending_draft_id: Mapped[Optional[int]] = mapped_column(
        BigInteger,
        nullable=True,
        default=None,
        comment="待确认操作绑定的方案草稿ID"
    )
    image_instruction: Mapped[Optional[str]] = mapped_column(
        Text,
        nullable=True,
        comment="累积的生图反馈指令"
    )
    processing: Mapped[int] = mapped_column(
        TINYINT,
        nullable=False,
        default=0,
        comment="是否正在处理：标记请求已抢占，防止结果写入冲突"
    )
    version: Mapped[int] = mapped_column(
        BigInteger,
        nullable=False,
        default=0,
        comment="对话版本号，用于乐观锁"
    )
    create_time: Mapped[DateTime] = mapped_column(
        DateTime,
        nullable=False,
        default=func.now(),
        comment="创建时间"
    )
    update_time: Mapped[DateTime] = mapped_column(
        DateTime,
        nullable=False,
        default=func.now(),
        onupdate=func.now(),
        comment="更新时间"
    )

    __table_args__ = (
        Index("uk_user_session", "user_id", "session_id", unique=True),
        Index("idx_user_update_time", "user_id", "update_time"),
        {"comment": "Agent流程状态表"}
    )
