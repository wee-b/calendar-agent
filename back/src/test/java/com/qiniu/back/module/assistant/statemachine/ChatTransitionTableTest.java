package com.qiniu.back.module.assistant.statemachine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatTransitionTableTest {
    private final ChatTransitionTable table = new ChatTransitionTable();

    @Test void allPairsAreDefinedAndTemporaryQueriesKeepTheMainFlow() {
        for (ConversationStage stage : ConversationStage.values()) {
            for (UserSignal signal : UserSignal.values()) assertNotNull(table.resolve(stage, signal));
            assertEquals(new ChatTransitionTable.TransitionRule(AgentType.CHAT, stage),
                    table.resolve(stage, UserSignal.NEW_QUERY));
            assertEquals(new ChatTransitionTable.TransitionRule(AgentType.CHAT, stage),
                    table.resolve(stage, UserSignal.NEW_CHAT));
        }
    }

    @Test void ambiguousConfirmationNeverSyncsPlansOrGeneratesImages() {
        assertEquals(AgentType.PLANNER, table.resolve(ConversationStage.PLAN, UserSignal.CONFIRM).agent());
        assertEquals(AgentType.CHAT, table.resolve(ConversationStage.IMAGE, UserSignal.CONFIRM).agent());
        assertEquals(AgentType.CHAT, table.resolve(ConversationStage.CHAT, UserSignal.CONFIRM).agent());
        assertEquals(AgentType.EXECUTOR, table.resolve(ConversationStage.EXECUTE, UserSignal.CONFIRM).agent());
    }

    @Test void imagesKeepAPathBackToPlanSyncAndNewWritesAlwaysWait() {
        assertEquals(new ChatTransitionTable.TransitionRule(AgentType.IMAGE, ConversationStage.IMAGE),
                table.resolve(ConversationStage.PLAN, UserSignal.GENERATE_PLAN_IMAGE));
        assertEquals(new ChatTransitionTable.TransitionRule(AgentType.EXECUTOR, ConversationStage.CHAT),
                table.resolve(ConversationStage.IMAGE, UserSignal.SYNC_PLAN));
        for (ConversationStage stage : ConversationStage.values()) {
            assertEquals(ConversationStage.EXECUTE, table.resolve(stage, UserSignal.NEW_EXECUTE).nextStage());
            assertEquals(ConversationStage.CHAT, table.resolve(stage, UserSignal.REJECT).nextStage());
        }
    }
}
