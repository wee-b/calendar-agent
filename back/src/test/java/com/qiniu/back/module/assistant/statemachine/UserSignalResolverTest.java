package com.qiniu.back.module.assistant.statemachine;

import com.qiniu.back.module.assistant.service.PlanDraftService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserSignalResolverTest {

    @Mock private AgentFlowStateService flowStateService;
    @Mock private PlanDraftService planDraftService;
    private UserSignalResolver resolver;
    private AgentFlowState plannerState;

    @BeforeEach
    void setUp() {
        resolver = new UserSignalResolver(flowStateService, planDraftService);
        plannerState = new AgentFlowState();
        plannerState.setCurrentAgent(AgentFlowStateService.AGENT_PLANNER);
    }

    @Test
    void rejectWinsBeforeOtherSignals() {
        when(flowStateService.isRejectMessage("取消")).thenReturn(true);
        assertEquals(UserSignal.REJECT, resolve("取消"));
    }

    @Test
    void explicitStandaloneQueryIsNewRequestEvenWhenItContainsADate() {
        stubNoDecision("另外帮我查一下明天的待办");
        assertEquals(UserSignal.NEW_REQUEST, resolve("另外帮我查一下明天的待办"));
    }

    @Test
    void planningConstraintIsModification() {
        stubNoDecision("改成每天2小时");
        assertEquals(UserSignal.MODIFY, resolve("改成每天2小时"));
    }

    @Test
    void ambiguousReplyStaysUnknown() {
        stubNoDecision("我再想想");
        assertEquals(UserSignal.UNKNOWN, resolve("我再想想"));
    }

    private UserSignal resolve(String message) {
        return resolver.resolve(ConversationStage.AWAITING_PLAN_CONFIRMATION, plannerState, message);
    }

    private void stubNoDecision(String message) {
        when(flowStateService.isRejectMessage(message)).thenReturn(false);
        when(planDraftService.isConfirmMessage(message)).thenReturn(false);
    }
}
