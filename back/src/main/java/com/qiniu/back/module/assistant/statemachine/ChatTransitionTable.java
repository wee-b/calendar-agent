package com.qiniu.back.module.assistant.statemachine;

import org.springframework.stereotype.Component;

import java.util.Map;




@Component
public class ChatTransitionTable {

    public record TransitionKey(
            ConversationStage stage,
            UserSignal signal
    ) {
    }

    public record TransitionRule(
            ChatNode targetNode
    ) {
    }


    private final Map<TransitionKey, TransitionRule> rules = Map.ofEntries(

            // READY：所有消息都是全新请求
            Map.entry(
                    key(ConversationStage.READY_FOR_INPUT, UserSignal.NEW_MESSAGE),
                    rule(ChatNode.ROUTE_MESSAGE)
            ),

            // 等待执行确认
            Map.entry(
                    key(ConversationStage.AWAITING_EXECUTION_CONFIRMATION, UserSignal.CONFIRM),
                    rule(ChatNode.EXECUTE_PENDING_ACTION)
            ),
            Map.entry(
                    key(ConversationStage.AWAITING_EXECUTION_CONFIRMATION, UserSignal.REJECT),
                    rule(ChatNode.CANCEL_PENDING_ACTION)
            ),
            Map.entry(
                    key(ConversationStage.AWAITING_EXECUTION_CONFIRMATION, UserSignal.MODIFY),
                    rule(ChatNode.MODIFY_PENDING_ACTION)
            ),
            Map.entry(
                    key(ConversationStage.AWAITING_EXECUTION_CONFIRMATION, UserSignal.NEW_REQUEST),
                    rule(ChatNode.EXPLAIN_PENDING_STATE)
            ),
            Map.entry(
                    key(ConversationStage.AWAITING_EXECUTION_CONFIRMATION, UserSignal.UNKNOWN),
                    rule(ChatNode.EXPLAIN_PENDING_STATE)
            ),

            // 等待开始规划确认
            Map.entry(
                    key(ConversationStage.AWAITING_PLAN_CONFIRMATION, UserSignal.CONFIRM),
                    rule(ChatNode.GENERATE_PLAN)
            ),
            Map.entry(
                    key(ConversationStage.AWAITING_PLAN_CONFIRMATION, UserSignal.REJECT),
                    rule(ChatNode.CANCEL_PENDING_ACTION)
            ),
            Map.entry(
                    key(ConversationStage.AWAITING_PLAN_CONFIRMATION, UserSignal.MODIFY),
                    rule(ChatNode.MODIFY_PENDING_ACTION)
            ),
            Map.entry(
                    key(ConversationStage.AWAITING_PLAN_CONFIRMATION, UserSignal.NEW_REQUEST),
                    rule(ChatNode.EXPLAIN_PENDING_STATE)
            ),
            Map.entry(
                    key(ConversationStage.AWAITING_PLAN_CONFIRMATION, UserSignal.UNKNOWN),
                    rule(ChatNode.EXPLAIN_PENDING_STATE)
            ),

            // 等待规划草稿反馈
            Map.entry(
                    key(ConversationStage.AWAITING_PLAN_FEEDBACK, UserSignal.CONFIRM),
                    rule(ChatNode.APPLY_PLAN)
            ),
            Map.entry(
                    key(ConversationStage.AWAITING_PLAN_FEEDBACK, UserSignal.REJECT),
                    rule(ChatNode.CANCEL_PENDING_ACTION)
            ),
            Map.entry(
                    key(ConversationStage.AWAITING_PLAN_FEEDBACK, UserSignal.MODIFY),
                    rule(ChatNode.REVISE_PLAN)
            ),
            Map.entry(
                    key(ConversationStage.AWAITING_PLAN_FEEDBACK, UserSignal.NEW_REQUEST),
                    rule(ChatNode.EXPLAIN_PENDING_STATE)
            ),
            Map.entry(
                    key(ConversationStage.AWAITING_PLAN_FEEDBACK, UserSignal.UNKNOWN),
                    rule(ChatNode.EXPLAIN_PENDING_STATE)
            )
    );

    public TransitionRule resolve(
            ConversationStage stage,
            UserSignal signal
    ) {
        TransitionRule rule = rules.get(new TransitionKey(stage, signal));

        if (rule == null) {
            throw new IllegalStateException(
                    "未定义状态转换: stage=" + stage + ", signal=" + signal
            );
        }

        return rule;
    }

    private static TransitionKey key(
            ConversationStage stage,
            UserSignal signal
    ) {
        return new TransitionKey(stage, signal);
    }

    private static TransitionRule rule(ChatNode node) {
        return new TransitionRule(node);
    }
}
