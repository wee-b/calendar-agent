package com.qiniu.back.module.assistant.statemachine;

import com.qiniu.back.module.assistant.agent.ExecutorAgent;
import com.qiniu.back.module.assistant.agent.ChatAgent;
import com.qiniu.back.module.assistant.agent.PlannerAgent;
import com.qiniu.back.module.assistant.domain.result.ChatDispatchResult;
import com.qiniu.back.module.assistant.service.PlanDraftService;
import com.qiniu.back.module.assistant.service.ProgressReporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExistingFlowStateProcessorTest {

    @Mock private AgentFlowStateService flowStateService;
    @Mock private PlanDraftService planDraftService;
    @Mock private UserSignalResolver signalResolver;
    @Mock private ChatAgent chatAgent;
    @Mock private PlannerAgent plannerAgent;
    @Mock private ExecutorAgent executorAgent;

    private ExistingFlowStateProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ExistingFlowStateProcessor(flowStateService, planDraftService, signalResolver,
                new ChatTransitionTable(), chatAgent, plannerAgent, executorAgent, new ProgressReporter());
    }

    @Test
    void shouldLetNextHandlerRunWhenNoFlowStateExists() {
        String message = "查询明天的安排";
        when(flowStateService.get(1L, "session-1")).thenReturn(Optional.empty());

        assertTrue(processor.tryHandle(1L, "session-1", message, null).isEmpty());
        verify(signalResolver, never()).resolve(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldClearStateWhenUserRejectsPendingFlow() {
        String message = "取消";
        AgentFlowState state = state(AgentFlowStateService.AGENT_EXECUTOR,
                AgentFlowStateService.AGENT_EXECUTOR, AgentFlowStateService.STAGE_WAIT_CONFIRM);
        mockState(state, ConversationStage.AWAITING_EXECUTION_CONFIRMATION, UserSignal.REJECT);
        ChatDispatchResult result = processor.tryHandle(1L, "session-1", message, null).orElseThrow();

        assertEquals("CANCEL", result.dispatchType());
        assertEquals(AgentFlowStateService.STAGE_IDLE, result.flowStage());
        verify(flowStateService).clear(1L, "session-1");
    }

    @Test
    void shouldGeneratePlanWhenPlannerFlowIsConfirmed() {
        String message = "确认";
        AgentFlowState state = state(AgentFlowStateService.AGENT_PLANNER,
                AgentFlowStateService.AGENT_PLANNER, AgentFlowStateService.STAGE_WAIT_CONFIRM);
        state.setPendingTask("制定复习计划");
        mockState(state, ConversationStage.AWAITING_PLAN_CONFIRMATION, UserSignal.CONFIRM);
        when(plannerAgent.generateDraft("制定复习计划"))
                .thenReturn(new PlannerAgent.GeneratedPlan(101L, "规划草稿"));
        AgentFlowState feedbackState = state(AgentFlowStateService.AGENT_PLANNER,
                AgentFlowStateService.AGENT_EXECUTOR, AgentFlowStateService.STAGE_WAIT_FEEDBACK);
        when(flowStateService.waitPlanFeedback(1L, "session-1", "制定复习计划", 101L, "规划草稿"))
                .thenReturn(feedbackState);

        ChatDispatchResult result = processor.tryHandle(1L, "session-1", message, null).orElseThrow();

        assertEquals("PLAN", result.dispatchType());
        assertEquals("规划草稿\n\n需要同步到日历中吗？", result.aiResult());
        assertEquals(AgentFlowStateService.STAGE_WAIT_FEEDBACK, result.flowStage());
        verify(flowStateService).waitPlanFeedback(1L, "session-1", "制定复习计划", 101L, "规划草稿");
    }

    @Test
    void shouldKeepPendingFlowUntouchedForNewRequest() {
        String message = "另外帮我查一下明天的待办";
        AgentFlowState state = state(AgentFlowStateService.AGENT_PLANNER,
                AgentFlowStateService.AGENT_PLANNER, AgentFlowStateService.STAGE_WAIT_CONFIRM);
        mockState(state, ConversationStage.AWAITING_PLAN_CONFIRMATION, UserSignal.NEW_REQUEST);

        ChatDispatchResult result = processor.tryHandle(1L, "session-1", message, null).orElseThrow();

        assertEquals("PENDING_NEW_REQUEST", result.dispatchType());
        verify(flowStateService, never()).updatePendingTask(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldRegeneratePlanWhenUserRefinesGeneratedPlan() {
        String message = "改成每天2小时";
        AgentFlowState state = state(AgentFlowStateService.AGENT_PLANNER,
                AgentFlowStateService.AGENT_EXECUTOR, AgentFlowStateService.STAGE_WAIT_FEEDBACK);
        state.setPendingTask("原始规划");
        mockState(state, ConversationStage.AWAITING_PLAN_FEEDBACK, UserSignal.MODIFY);
        String revisedTask = "原始规划\n用户补充/修改：改成每天2小时";
        when(plannerAgent.generateDraft(revisedTask))
                .thenReturn(new PlannerAgent.GeneratedPlan(102L, "新规划草稿"));
        when(flowStateService.waitPlanFeedback(
                1L, "session-1", revisedTask, 102L, "新规划草稿"))
                .thenReturn(state);

        ChatDispatchResult result = processor.tryHandle(1L, "session-1", message, null).orElseThrow();

        assertEquals("PLAN_REFINE", result.dispatchType());
        assertEquals(AgentFlowStateService.STAGE_WAIT_FEEDBACK, result.flowStage());
        verify(flowStateService).waitPlanFeedback(
                1L, "session-1", revisedTask, 102L, "新规划草稿");
        verify(flowStateService, never()).updatePendingTask(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldNotExecuteWhenAnotherRequestClaimedTheState() {
        String message = "确认";
        AgentFlowState state = state(AgentFlowStateService.AGENT_EXECUTOR,
                AgentFlowStateService.AGENT_EXECUTOR, AgentFlowStateService.STAGE_WAIT_CONFIRM);
        state.setPendingTask("创建待办");
        mockState(state, ConversationStage.AWAITING_EXECUTION_CONFIRMATION, UserSignal.CONFIRM);
        when(flowStateService.claim(state)).thenReturn(false);

        ChatDispatchResult result = processor.tryHandle(1L, "session-1", message, null).orElseThrow();

        assertEquals("PROCESSING", result.dispatchType());
        verify(executorAgent, never()).execute(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldUseChatAgentForConfirmedSingleDayAction() {
        String message = "确认";
        AgentFlowState state = state(AgentFlowStateService.AGENT_CHAT,
                AgentFlowStateService.AGENT_CHAT, AgentFlowStateService.STAGE_WAIT_CONFIRM);
        state.setPendingTask("删除明天的跑步任务");
        mockState(state, ConversationStage.AWAITING_EXECUTION_CONFIRMATION, UserSignal.CONFIRM);
        when(chatAgent.executeSingleDay(state.getPendingTask())).thenReturn("已删除");

        ChatDispatchResult result = processor.tryHandle(1L, "session-1", message, null).orElseThrow();

        assertEquals("已删除", result.aiResult());
        verify(chatAgent).executeSingleDay("删除明天的跑步任务");
        verify(executorAgent, never()).execute(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldLoadTheDraftBoundToTheFlowState() {
        String message = "确认";
        AgentFlowState state = state(AgentFlowStateService.AGENT_PLANNER,
                AgentFlowStateService.AGENT_EXECUTOR, AgentFlowStateService.STAGE_WAIT_FEEDBACK);
        state.setPendingDraftId(202L);
        mockState(state, ConversationStage.AWAITING_PLAN_FEEDBACK, UserSignal.CONFIRM);
        when(planDraftService.findPending(1L, "session-1", 202L)).thenReturn(Optional.empty());

        processor.tryHandle(1L, "session-1", message, null).orElseThrow();

        verify(planDraftService).findPending(1L, "session-1", 202L);
        verify(planDraftService, never()).findLatestPending(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
    }

    private void mockState(AgentFlowState state, ConversationStage stage, UserSignal signal) {
        when(flowStateService.get(1L, "session-1")).thenReturn(Optional.of(state));
        when(flowStateService.isProcessing(state)).thenReturn(false);
        when(flowStateService.resolveStage(state)).thenReturn(stage);
        when(signalResolver.resolve(stage, state, contextMessage(signal))).thenReturn(signal);
        if (signal != UserSignal.NEW_REQUEST && signal != UserSignal.UNKNOWN) {
            when(flowStateService.claim(state)).thenReturn(true);
        }
    }

    private String contextMessage(UserSignal signal) {
        return switch (signal) {
            case REJECT -> "取消";
            case CONFIRM -> "确认";
            case NEW_REQUEST -> "另外帮我查一下明天的待办";
            case MODIFY -> "改成每天2小时";
            default -> "我再想想";
        };
    }

    private AgentFlowState state(String currentAgent, String nextAgent, String stage) {
        AgentFlowState state = new AgentFlowState();
        state.setStateId(10L);
        state.setCurrentAgent(currentAgent);
        state.setNextAgent(nextAgent);
        state.setStage(stage);
        return state;
    }
}
