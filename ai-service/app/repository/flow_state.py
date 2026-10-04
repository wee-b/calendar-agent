"""会话流程状态的持久化与版本认领。"""

from sqlalchemy import delete, func, select, update
from sqlalchemy.dialects.mysql import insert
from sqlalchemy.ext.asyncio import AsyncEngine

from app.core.exception.error_code import ErrorCode
from app.core.exception.exceptions import BusinessException
from app.db.session import get_session_factory, session_factory_for_engine
from app.models.agent_flow_state import YlAgentFlowState
from app.models.ai_session import AiSession
from app.schemas.statemachine.flow import ConversationStage, PendingTask
from app.schemas.statemachine.transitions import AgentType
from app.repository.chat import ChatRepository
from app.schemas.chat.history import ChatMessageCreate, DocumentReference
from app.schemas.chat.timeline import AgentStep


class FlowStateRepository:
    def __init__(self, engine: AsyncEngine | None = None) -> None:
        self.sessions = session_factory_for_engine(engine) if engine else get_session_factory()

    @staticmethod
    def _validate_key(user_id: int, session_id: str) -> None:
        if user_id <= 0 or not session_id or not session_id.strip() or len(session_id) > 64:
            raise ValueError("用户 ID 或会话 ID 无效")

    @staticmethod
    def _identity(state: YlAgentFlowState):
        return (
            YlAgentFlowState.state_id == state.state_id,
            YlAgentFlowState.user_id == state.user_id,
            YlAgentFlowState.session_id == state.session_id,
            YlAgentFlowState.version == state.version,
        )

    async def get(self, user_id: int, session_id: str) -> YlAgentFlowState | None:
        """只读取指定用户的会话状态；不存在时不创建记录。"""
        self._validate_key(user_id, session_id)
        async with self.sessions() as session:
            return await session.scalar(select(YlAgentFlowState).where(
                YlAgentFlowState.user_id == user_id,
                YlAgentFlowState.session_id == session_id,
            ))

    async def get_or_create(self, user_id: int, session_id: str) -> YlAgentFlowState:
        """首次使用时创建状态；先锁会话行，避免与删除会话并发复活状态。"""
        self._validate_key(user_id, session_id)
        async with self.sessions() as session:
            async with session.begin():
                chat_insert = insert(AiSession).values(user_id=user_id, session_id=session_id)
                await session.execute(chat_insert.on_duplicate_key_update(id=AiSession.id))
                chat_session = (await session.execute(select(AiSession).where(
                    AiSession.user_id == user_id, AiSession.session_id == session_id,
                ).with_for_update())).scalar_one()
                if chat_session.deleted_flag:
                    raise BusinessException(ErrorCode.CHAT_SESSION_DELETED)

                state_insert = insert(YlAgentFlowState).values(
                    user_id=user_id, session_id=session_id,
                )
                await session.execute(state_insert.on_duplicate_key_update(
                    state_id=YlAgentFlowState.state_id,
                ))
                return (await session.execute(select(YlAgentFlowState).where(
                    YlAgentFlowState.user_id == user_id,
                    YlAgentFlowState.session_id == session_id,
                ))).scalar_one()

    async def claim(self, state: YlAgentFlowState) -> bool:
        """原子认领一轮请求；版本过期或正在处理时返回 False。"""
        self._validate_key(state.user_id, state.session_id)
        if state.processing:
            return False
        async with self.sessions() as session:
            async with session.begin():
                result = await session.execute(update(YlAgentFlowState).where(
                    *self._identity(state), YlAgentFlowState.processing == 0,
                ).values(
                    processing=1,
                    version=YlAgentFlowState.version + 1,
                    update_time=func.current_timestamp(),
                ))
                claimed = result.rowcount == 1
        if claimed:
            state.processing = 1
            state.version += 1
        return claimed

    async def complete(
        self, state: YlAgentFlowState, next_stage: ConversationStage,
        pending: PendingTask, agent: AgentType,
        *, message: str | None = None, reply: str | None = None, elapsed_ms: int = 0,
        agent_steps: list[AgentStep] | None = None,
        document_references: list[DocumentReference] | None = None,
        dispatch_type: str | None = None,
    ) -> None:
        """只有持有当前版本的请求可以提交阶段、产物并释放认领。"""
        self._validate_key(state.user_id, state.session_id)
        async with self.sessions() as session:
            async with session.begin():
                # 与删除/撤回保持同一锁顺序：会话 -> 流程状态。
                chat_session = await ChatRepository._lock_session(session, state.user_id, state.session_id)
                if chat_session.deleted_flag:
                    raise BusinessException(ErrorCode.CHAT_SESSION_DELETED)
                result = await session.execute(update(YlAgentFlowState).where(
                    *self._identity(state), YlAgentFlowState.processing == 1,
                ).values(
                    stage=next_stage.name,
                    current_agent=agent.name,
                    next_agent=self.owner(next_stage),
                    pending_task=pending.task,
                    pending_draft_id=pending.draft_id,
                    pending_payload=pending.plan_preview,
                    image_instruction=pending.image_instruction,
                    processing=0,
                    version=YlAgentFlowState.version + 1,
                    update_time=func.current_timestamp(),
                ))
                if result.rowcount != 1:
                    raise BusinessException(ErrorCode.CHAT_SESSION_PROCESSING,
                                            message="会话状态已变化，请刷新后重试")
                if message is not None and reply is not None:
                    await ChatRepository.append_locked(session, chat_session, [
                        ChatMessageCreate(role="user", content=message,
                                          document_references=document_references or []),
                        ChatMessageCreate(role="assistant", content=reply, response_time_ms=elapsed_ms,
                                          agent_steps=agent_steps, dispatch_type=dispatch_type),
                    ])
        state.stage = next_stage.name
        state.current_agent = agent.name
        state.next_agent = self.owner(next_stage)
        state.pending_task = pending.task
        state.pending_draft_id = pending.draft_id
        state.pending_payload = pending.plan_preview
        state.image_instruction = pending.image_instruction
        state.processing = 0
        state.version += 1

    async def release_claim(self, state: YlAgentFlowState) -> None:
        """仅用于尚未开始写工具调用的失败；保留原阶段和产物。"""
        self._validate_key(state.user_id, state.session_id)
        async with self.sessions() as session:
            async with session.begin():
                result = await session.execute(update(YlAgentFlowState).where(
                    *self._identity(state), YlAgentFlowState.processing == 1,
                ).values(
                    processing=0,
                    version=YlAgentFlowState.version + 1,
                    update_time=func.current_timestamp(),
                ))
                if result.rowcount != 1:
                    raise BusinessException(ErrorCode.CHAT_SESSION_PROCESSING,
                                            message="会话状态已变化，请刷新后重试")
        state.processing = 0
        state.version += 1

    async def delete(self, user_id: int, session_id: str) -> bool:
        """删除空闲状态；正在处理的状态必须先完成或核实结果。"""
        self._validate_key(user_id, session_id)
        async with self.sessions() as session:
            async with session.begin():
                state = (await session.execute(select(YlAgentFlowState).where(
                    YlAgentFlowState.user_id == user_id,
                    YlAgentFlowState.session_id == session_id,
                ).with_for_update())).scalar_one_or_none()
                if state is None:
                    return False
                if state.processing:
                    raise BusinessException(ErrorCode.CHAT_SESSION_PROCESSING)
                await session.execute(delete(YlAgentFlowState).where(
                    YlAgentFlowState.state_id == state.state_id,
                ))
                return True

    @staticmethod
    def resolve_stage(state: YlAgentFlowState) -> ConversationStage:
        return ConversationStage[state.stage]

    @staticmethod
    def pending(state: YlAgentFlowState) -> PendingTask:
        return PendingTask(
            task=state.pending_task, draft_id=state.pending_draft_id,
            plan_preview=state.pending_payload,
            image_instruction=state.image_instruction,
        )

    @staticmethod
    def owner(stage: ConversationStage) -> str:
        return {
            ConversationStage.CHAT: "NONE",
            ConversationStage.PLAN: AgentType.PLANNER.name,
            ConversationStage.EXECUTE: AgentType.EXECUTOR.name,
            ConversationStage.IMAGE: AgentType.IMAGE.name,
        }[stage]
