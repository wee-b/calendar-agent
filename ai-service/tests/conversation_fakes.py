"""旧 HTTP 契约测试使用真实主图，用替身隔离 Route 模型和状态数据库。"""

from contextlib import contextmanager
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from app.all_graph.nodes.route_node import RouteDecision
from app.repository.flow_state import FlowStateRepository
from app.schemas.statemachine.flow import UserSignal


class MemoryFlowRepository:
    resolve_stage = staticmethod(FlowStateRepository.resolve_stage)
    pending = staticmethod(FlowStateRepository.pending)

    def __init__(self):
        self.states = {}
        self.saved = []
        self.releases = 0

    async def get_or_create(self, user_id, session_id):
        return self.states.setdefault((user_id, session_id), SimpleNamespace(
            user_id=user_id, session_id=session_id, version=0, stage="CHAT", processing=0,
            pending_task=None, pending_draft_id=None, pending_payload=None, image_instruction=None))

    async def claim(self, state):
        if state.processing:
            return False
        state.processing = 1
        state.version += 1
        return True

    async def complete(self, state, stage, pending, agent, **round_data):
        state.stage, state.processing = stage.name, 0
        state.version += 1
        state.pending_task, state.pending_draft_id = pending.task, pending.draft_id
        state.pending_payload, state.image_instruction = pending.plan_preview, pending.image_instruction
        self.saved.append((state.user_id, state.session_id, round_data))

    async def release_claim(self, state):
        state.processing = 0
        state.version += 1
        self.releases += 1


@contextmanager
def http_conversation_fakes():
    class Flow(MemoryFlowRepository):
        async def complete(self, state, stage, pending, agent, **round_data):
            # HTTP 契约测试已有消息仓储替身，继续核验仅保存一次。
            from app.service.chat import ChatRepository
            await ChatRepository().save_round(state.user_id, state.session_id,
                                             round_data["message"], round_data["reply"], round_data["elapsed_ms"])
            await super().complete(state, stage, pending, agent, **round_data)

    with patch("app.service.chat.FlowStateRepository", return_value=Flow()), \
         patch("app.all_graph.nodes.route_node.RouteAgent.route",
               new=AsyncMock(return_value=RouteDecision(signal=UserSignal.NEW_QUERY))):
        yield
