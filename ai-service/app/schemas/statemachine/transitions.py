from enum import Enum

from pydantic import BaseModel

from app.schemas.statemachine.flow import ConversationStage, UserSignal


class AgentType(Enum):
    CHAT, PLANNER, EXECUTOR, IMAGE = range(1, 5)


class ChatTransitionTable:

    class TransitionRule(BaseModel):
        agent: AgentType
        next_stage: ConversationStage

    def __init__(self) -> None:
        # 未单独配置的信号交给 Chat，且保留当前主流程阶段。
        self.rules: dict[ConversationStage, dict[UserSignal, ChatTransitionTable.TransitionRule]] = {
            stage: {
                signal: self.TransitionRule(agent=AgentType.CHAT, next_stage=stage)
                for signal in UserSignal
            }
            for stage in ConversationStage
        }

        for stage in ConversationStage:
            self._put(stage, UserSignal.NEW_PLAN, AgentType.PLANNER, ConversationStage.PLAN)
            self._put(stage, UserSignal.NEW_EXECUTE, AgentType.EXECUTOR, ConversationStage.EXECUTE)
            self._put(stage, UserSignal.REJECT, AgentType.CHAT, ConversationStage.CHAT)

        self._put(ConversationStage.PLAN, UserSignal.CONFIRM, AgentType.PLANNER, ConversationStage.PLAN)
        self._put(ConversationStage.PLAN, UserSignal.MODIFY, AgentType.PLANNER, ConversationStage.PLAN)
        self._put(ConversationStage.PLAN, UserSignal.SYNC_PLAN, AgentType.EXECUTOR, ConversationStage.CHAT)
        self._put(ConversationStage.PLAN, UserSignal.GENERATE_PLAN_IMAGE, AgentType.IMAGE, ConversationStage.IMAGE)
        self._put(ConversationStage.EXECUTE, UserSignal.CONFIRM, AgentType.EXECUTOR, ConversationStage.CHAT)
        self._put(ConversationStage.EXECUTE, UserSignal.MODIFY, AgentType.EXECUTOR, ConversationStage.EXECUTE)
        self._put(ConversationStage.IMAGE, UserSignal.MODIFY, AgentType.IMAGE, ConversationStage.IMAGE)
        self._put(ConversationStage.IMAGE, UserSignal.GENERATE_PLAN_IMAGE, AgentType.IMAGE, ConversationStage.IMAGE)
        self._put(ConversationStage.IMAGE, UserSignal.SYNC_PLAN, AgentType.EXECUTOR, ConversationStage.CHAT)

    def resolve(self, stage: ConversationStage, signal: UserSignal) -> TransitionRule:
        if not isinstance(stage, ConversationStage) or not isinstance(signal, UserSignal):
            raise ValueError("状态和信号必须是有效枚举值")
        return self.rules[stage][signal]

    def _put(
        self,
        stage: ConversationStage,
        signal: UserSignal,
        agent: AgentType,
        next_stage: ConversationStage,
    ) -> None:
        self.rules[stage][signal] = self.TransitionRule(agent=agent, next_stage=next_stage)
