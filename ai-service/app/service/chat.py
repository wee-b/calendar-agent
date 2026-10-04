"""会话与消息的读写服务；模型对话由 ChatNode 负责。"""

from uuid import uuid4

from fastapi import HTTPException

from app.repository.chat import ChatRepository
from app.repository.documents import DocumentRepository
from app.repository.chat_session import ChatSessionRepository
from app.repository.flow_state import FlowStateRepository
from app.models.agent_flow_state import YlAgentFlowState
from app.schemas.chat.history import (
    ChatHistoryItem, ChatHistoryPage, ChatSessionItem, DocumentReference, DEFAULT_HISTORY_LIMIT,
)
from app.schemas.chat.timeline import AgentStep
from app.schemas.statemachine.route_context import RouteContext, RouteContextMessage
from app.schemas.statemachine.flow import ConversationStage, PendingTask
from app.schemas.statemachine.transitions import AgentType
from app.schemas.chat.chat import NewSessionResult


class ChatService:
    """只封装会话、消息和流程状态的增删改查。

    模型、记忆抽取、摘要及后台任务调度必须留在 LangGraph 节点；
    此处只接收节点已生成的数据，并交给仓储执行事务写入。
    """

    def __init__(self, flow_state_repository: FlowStateRepository | None = None) -> None:
        # 延迟创建仓储，历史接口无需为了读取消息而初始化流程状态数据库连接。
        self._flow_state_repository = flow_state_repository

    @property
    def flow_states(self) -> FlowStateRepository:
        if self._flow_state_repository is None:
            self._flow_state_repository = FlowStateRepository()
        return self._flow_state_repository

    @staticmethod
    def new_session() -> NewSessionResult:
        """首次保存消息时才创建会话元数据。"""
        return NewSessionResult(sessionId=str(uuid4()))

    async def delete_session(self, user_id: int, session_id: str) -> None:
        await ChatRepository().delete_session(user_id, session_id)

    async def delete_last_round(self, user_id: int, session_id: str) -> None:
        await ChatRepository().delete_last_round(user_id, session_id)

    async def get_history(
        self, user_id: int, session_id: str,
        before_id: int | None = None, limit: int = DEFAULT_HISTORY_LIMIT,
    ) -> ChatHistoryPage:
        """页面每次读取有限条消息，页内按旧到新展示。"""
        rows, has_more = await ChatRepository().list_history(
            user_id, session_id, before_id, limit
        )
        items = [
            ChatHistoryItem(
                dialogueId=row.dialogue_id,
                role=row.role,
                content=row.content,
                createTime=row.create_time,
                responseTimeMs=row.response_time_ms,
                agentSteps=row.agent_steps or [],
                documentReferences=row.document_references or [],
            )
            for row in reversed(rows)
        ]
        return ChatHistoryPage(
            items=items, hasMore=has_more,
            nextBeforeId=rows[-1].dialogue_id if has_more else None,
        )

    async def list_sessions(self, user_id: int) -> list[ChatSessionItem]:
        """只查询会话元数据，避免读取全部消息正文。"""
        rows = await ChatSessionRepository().list_sessions(user_id)
        return [
            ChatSessionItem(
                sessionId=row.session_id, title=row.title or "新对话",
                createTime=row.create_time, lastMessageTime=row.last_message_time,
                messageCount=row.message_count,
            )
            for row in rows
        ]

    async def document_references(self, user_id: int, ids: list[int]) -> list[DocumentReference]:
        """在本轮开始时保存文档名称快照，不信任客户端名称或跨用户文件 ID。"""
        unique_ids = list(dict.fromkeys(ids))
        if not unique_ids:
            return []
        files = await DocumentRepository().ready_files(user_id, unique_ids)
        by_id = {entry.file_id: entry for entry in files}
        if any(file_id not in by_id for file_id in unique_ids):
            raise HTTPException(400, "引用的文档不存在、未解析完成或无权访问")
        return [DocumentReference(fileId=file_id, fileName=by_id[file_id].file_name)
                for file_id in unique_ids]

    async def get_route_context(
        self, user_id: int, session_id: str,
        current_message: str | None = None, through_id: int | None = None,
    ) -> RouteContext:
        """供 RouteAgent 直接调用；current_message 仅传入尚未入库的本次输入。

        已入库的本次消息使用 through_id 定位上界，不再重复传 current_message。
        """
        rows = await ChatRepository().route_context_messages(user_id, session_id, through_id)
        context = RouteContext(sessionId=session_id)
        for row in rows:
            message = RouteContextMessage(
                dialogueId=row.dialogue_id, role=row.role, content=row.content
            )
            if row.role == "assistant":
                context.previousReply = message
            else:
                context.userMessages.append(message)
        if current_message is not None:
            context.userMessages.append(RouteContextMessage(role="user", content=current_message))
        return context

    async def recent_messages(self, user_id: int, session_id: str):
        """读取模型可用的近期消息；提示词由调用方组装。"""
        return await ChatRepository().recent_messages(user_id, session_id)

    async def save_round(
        self, user_id: int, session_id: str, message: str,
        answer: str, elapsed_ms: int,
    ) -> None:
        """一次事务保存本轮用户输入与完整助手回复。"""
        await ChatRepository().save_round(
            user_id, session_id, message, answer, elapsed_ms,
        )

    async def get_flow_state(self, user_id: int, session_id: str) -> YlAgentFlowState | None:
        """读取已存在的流程状态，不创建新记录。"""
        return await self.flow_states.get(user_id, session_id)

    async def get_or_create_flow_state(self, user_id: int, session_id: str) -> YlAgentFlowState:
        """首次对话时建立流程状态，并检查所属会话未删除。"""
        return await self.flow_states.get_or_create(user_id, session_id)

    async def claim_flow_state(self, state: YlAgentFlowState) -> bool:
        """以版本号原子认领本轮处理；失败表示已有请求在处理。"""
        return await self.flow_states.claim(state)

    async def complete_flow_state(
        self, state: YlAgentFlowState, next_stage: ConversationStage,
        pending: PendingTask, agent: AgentType,
        *, message: str | None = None, reply: str | None = None, elapsed_ms: int = 0,
        agent_steps: list[AgentStep] | None = None,
        document_references: list[DocumentReference] | None = None,
    ) -> None:
        """提交阶段与待处理任务，并释放本轮认领。"""
        await self.flow_states.complete(state, next_stage, pending, agent,
                                        message=message, reply=reply, elapsed_ms=elapsed_ms,
                                        agent_steps=agent_steps, document_references=document_references)

    async def release_flow_state_claim(self, state: YlAgentFlowState) -> None:
        """仅在尚未开始外部写操作的失败路径释放认领。"""
        await self.flow_states.release_claim(state)

    async def delete_flow_state(self, user_id: int, session_id: str) -> bool:
        """单独删除空闲流程状态；删除整个会话仍走 delete_session 的事务。"""
        return await self.flow_states.delete(user_id, session_id)

    def resolve_flow_stage(self, state: YlAgentFlowState) -> ConversationStage:
        return self.flow_states.resolve_stage(state)

    def flow_pending(self, state: YlAgentFlowState) -> PendingTask:
        return self.flow_states.pending(state)
