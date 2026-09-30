package com.qiniu.back.module.assistant.statemachine;

import org.springframework.stereotype.Component;
import java.util.EnumMap;
import java.util.Map;

/** 唯一选路表：(会话阶段, 用户信号) -> (Agent, 成功后的阶段)。 */
@Component
public class ChatTransitionTable {
    public record TransitionRule(AgentType agent, ConversationStage nextStage) {}
    private final Map<ConversationStage, Map<UserSignal, TransitionRule>> rules =
            new EnumMap<>(ConversationStage.class);

    public ChatTransitionTable() {
        for (ConversationStage stage : ConversationStage.values()) {
            Map<UserSignal, TransitionRule> row = new EnumMap<>(UserSignal.class);
            // 无匹配的上下文操作由 Chat 提示补充，保留当前主流程。
            for (UserSignal signal : UserSignal.values()) {
                row.put(signal, new TransitionRule(AgentType.CHAT, stage));
            }
            row.put(UserSignal.NEW_PLAN, new TransitionRule(AgentType.PLANNER, ConversationStage.PLAN));
            row.put(UserSignal.NEW_EXECUTE, new TransitionRule(AgentType.EXECUTOR, ConversationStage.EXECUTE));
            row.put(UserSignal.REJECT, new TransitionRule(AgentType.CHAT, ConversationStage.CHAT));
            rules.put(stage, row);
        }
        put(ConversationStage.PLAN, UserSignal.CONFIRM, AgentType.PLANNER, ConversationStage.PLAN);
        put(ConversationStage.PLAN, UserSignal.MODIFY, AgentType.PLANNER, ConversationStage.PLAN);
        put(ConversationStage.PLAN, UserSignal.SYNC_PLAN, AgentType.EXECUTOR, ConversationStage.CHAT);
        put(ConversationStage.PLAN, UserSignal.GENERATE_PLAN_IMAGE, AgentType.IMAGE, ConversationStage.IMAGE);
        put(ConversationStage.EXECUTE, UserSignal.CONFIRM, AgentType.EXECUTOR, ConversationStage.CHAT);
        put(ConversationStage.EXECUTE, UserSignal.MODIFY, AgentType.EXECUTOR, ConversationStage.EXECUTE);
        put(ConversationStage.IMAGE, UserSignal.MODIFY, AgentType.IMAGE, ConversationStage.IMAGE);
        put(ConversationStage.IMAGE, UserSignal.GENERATE_PLAN_IMAGE, AgentType.IMAGE, ConversationStage.IMAGE);
        put(ConversationStage.IMAGE, UserSignal.SYNC_PLAN, AgentType.EXECUTOR, ConversationStage.CHAT);
    }
    public TransitionRule resolve(ConversationStage stage, UserSignal signal) {
        if (stage == null || signal == null) throw new IllegalArgumentException("状态和信号不能为空");
        return rules.get(stage).get(signal);
    }
    private void put(ConversationStage stage, UserSignal signal, AgentType agent, ConversationStage next) {
        rules.get(stage).put(signal, new TransitionRule(agent, next));
    }
}
