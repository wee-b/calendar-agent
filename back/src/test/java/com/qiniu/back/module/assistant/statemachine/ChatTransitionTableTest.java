package com.qiniu.back.module.assistant.statemachine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatTransitionTableTest {

    private final ChatTransitionTable table = new ChatTransitionTable();

    @Test
    void readyStructuredSignalsMapToActions() {
        assertRule(ConversationStage.READY_FOR_INPUT, UserSignal.READY_CHAT,
                ChatNode.CHAT_DIALOGUE);
        assertRule(ConversationStage.READY_FOR_INPUT, UserSignal.READY_QUERY,
                ChatNode.QUERY_CALENDAR);
        assertRule(ConversationStage.READY_FOR_INPUT, UserSignal.READY_SINGLE_DAY_ACTION,
                ChatNode.EXECUTE_SINGLE_DAY_ACTION);
        assertRule(ConversationStage.READY_FOR_INPUT, UserSignal.READY_SINGLE_DAY_CONFIRM,
                ChatNode.PREPARE_SINGLE_DAY_CONFIRMATION);
        assertRule(ConversationStage.READY_FOR_INPUT, UserSignal.READY_EXECUTE,
                ChatNode.PREPARE_EXECUTION_CONFIRMATION);
        assertRule(ConversationStage.READY_FOR_INPUT, UserSignal.READY_PLAN,
                ChatNode.PREPARE_PLAN_CONFIRMATION);
    }

    @Test
    void pendingNewRequestsUseExplainNode() {
        assertRule(ConversationStage.AWAITING_EXECUTION_CONFIRMATION, UserSignal.NEW_REQUEST,
                ChatNode.EXPLAIN_PENDING_STATE);
        assertRule(ConversationStage.AWAITING_PLAN_CONFIRMATION, UserSignal.NEW_REQUEST,
                ChatNode.EXPLAIN_PENDING_STATE);
        assertRule(ConversationStage.AWAITING_PLAN_FEEDBACK, UserSignal.NEW_REQUEST,
                ChatNode.EXPLAIN_PENDING_STATE);
    }

    @Test
    void successfulSignalsResolveToExpectedActions() {
        assertRule(ConversationStage.AWAITING_EXECUTION_CONFIRMATION, UserSignal.CONFIRM,
                ChatNode.EXECUTE_PENDING_ACTION);
        assertRule(ConversationStage.AWAITING_PLAN_CONFIRMATION, UserSignal.CONFIRM,
                ChatNode.GENERATE_PLAN);
        assertRule(ConversationStage.AWAITING_PLAN_FEEDBACK, UserSignal.CONFIRM,
                ChatNode.APPLY_PLAN);
        assertRule(ConversationStage.AWAITING_PLAN_FEEDBACK, UserSignal.MODIFY,
                ChatNode.REVISE_PLAN);
        assertRule(ConversationStage.AWAITING_PLAN_CONFIRMATION, UserSignal.MODIFY,
                ChatNode.GENERATE_PLAN);
    }

    private void assertRule(ConversationStage stage, UserSignal signal, ChatNode node) {
        ChatTransitionTable.TransitionRule rule = table.resolve(stage, signal);
        assertEquals(node, rule.targetNode());
    }
}
