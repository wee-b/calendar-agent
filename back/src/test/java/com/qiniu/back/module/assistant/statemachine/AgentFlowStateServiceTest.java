package com.qiniu.back.module.assistant.statemachine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentFlowStateServiceTest {

    private final AgentFlowStateService service = new AgentFlowStateService();

    @Test
    void recognizesExplicitlyNegatedPendingActions() {
        assertTrue(service.isRejectMessage("不要同步"));
        assertTrue(service.isRejectMessage("不需要同步到日历"));
        assertTrue(service.isRejectMessage("不要执行"));
        assertTrue(service.isRejectMessage("不可以"));
        assertFalse(service.isRejectMessage("不要每天安排任务"));
    }
}
