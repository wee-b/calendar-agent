package com.qiniu.back.module.assistant.statemachine;

import com.qiniu.back.module.assistant.agent.ExecutorAgent;
import com.qiniu.back.module.assistant.agent.PlannerAgent;
import com.qiniu.back.module.assistant.agent.ImageAgent;
import com.qiniu.back.module.assistant.domain.result.ChatDispatchResult;
import com.qiniu.back.module.assistant.domain.model.PlanDraft;
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
    @Mock private PlannerAgent plannerAgent;
    @Mock private ExecutorAgent executorAgent;
    @Mock private ImageAgent imageAgent;

    private ExistingFlowStateProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ExistingFlowStateProcessor(flowStateService, planDraftService,
                new ChatTransitionTable(), plannerAgent, executorAgent, imageAgent,
                new ProgressReporter());
    }

    @Test
    void shouldLetNextHandlerRunWhenNoFlowStateExists() {
        String message = "查询明天的安排";
        assertTrue(processor.tryHandle(
                1L, "session-1", message, null, UserSignal.READY_QUERY, null).isEmpty());
    }

    @Test
    void shouldClearStateWhenUserRejectsPendingFlow() {
        String message = "取消";
        AgentFlowState state = state(AgentFlowStateService.AGENT_EXECUTOR,
                AgentFlowStateService.AGENT_EXECUTOR, AgentFlowStateService.STAGE_WAIT_CONFIRM);
        mockState(state, ConversationStage.AWAITING_EXECUTION_CONFIRMATION, UserSignal.REJECT);
        ChatDispatchResult result = processor.tryHandle(
                1L, "session-1", message, state, UserSignal.REJECT, null).orElseThrow();

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

        ChatDispatchResult result = processor.tryHandle(
                1L, "session-1", message, state, UserSignal.CONFIRM, null).orElseThrow();

        assertEquals("PLAN", result.dispatchType());
        assertEquals("规划草稿\n\n接下来需要我**同步到日历**，还是**生成一张规划示意图**？", result.aiResult());
        assertEquals(AgentFlowStateService.STAGE_WAIT_FEEDBACK, result.flowStage());
        verify(flowStateService).waitPlanFeedback(1L, "session-1", "制定复习计划", 101L, "规划草稿");
    }

    @Test
    void shouldGenerateImmediatelyAfterUserSuppliesClarification() {
        String message = "预算3000元，玩三天，喜欢美食";
        AgentFlowState state = state(AgentFlowStateService.AGENT_PLANNER,
                AgentFlowStateService.AGENT_PLANNER, AgentFlowStateService.STAGE_WAIT_CONFIRM);
        state.setPendingTask("帮我规划一次旅行");
        mockState(state, ConversationStage.AWAITING_PLAN_CONFIRMATION, UserSignal.MODIFY);
        String refined = "帮我规划一次旅行\n用户补充/修改：" + message;
        when(plannerAgent.generateDraft(refined))
                .thenReturn(new PlannerAgent.GeneratedPlan(103L, "补充后的规划草稿"));
        AgentFlowState feedbackState = state(AgentFlowStateService.AGENT_PLANNER,
                AgentFlowStateService.AGENT_EXECUTOR, AgentFlowStateService.STAGE_WAIT_FEEDBACK);
        when(flowStateService.waitPlanFeedback(
                1L, "session-1", refined, 103L, "补充后的规划草稿"))
                .thenReturn(feedbackState);

        ChatDispatchResult result = processor.tryHandle(
                1L, "session-1", message, state, UserSignal.MODIFY, null).orElseThrow();

        assertEquals("PLAN", result.dispatchType());
        assertEquals("补充后的规划草稿\n\n接下来需要我**同步到日历**，还是**生成一张规划示意图**？", result.aiResult());
        verify(flowStateService, never()).updatePendingTask(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldKeepPendingFlowUntouchedForNewRequest() {
        String message = "另外帮我查一下明天的待办";
        AgentFlowState state = state(AgentFlowStateService.AGENT_PLANNER,
                AgentFlowStateService.AGENT_PLANNER, AgentFlowStateService.STAGE_WAIT_CONFIRM);
        mockState(state, ConversationStage.AWAITING_PLAN_CONFIRMATION, UserSignal.NEW_REQUEST);

        ChatDispatchResult result = processor.tryHandle(
                1L, "session-1", message, state, UserSignal.NEW_REQUEST, null).orElseThrow();

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

        ChatDispatchResult result = processor.tryHandle(
                1L, "session-1", message, state, UserSignal.MODIFY, null).orElseThrow();

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

        ChatDispatchResult result = processor.tryHandle(
                1L, "session-1", message, state, UserSignal.CONFIRM, null).orElseThrow();

        assertEquals("PROCESSING", result.dispatchType());
        verify(executorAgent, never()).execute(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldUseExecutorForLegacyConfirmedChatAction() {
        String message = "确认";
        AgentFlowState state = state(AgentFlowStateService.AGENT_CHAT,
                AgentFlowStateService.AGENT_CHAT, AgentFlowStateService.STAGE_WAIT_CONFIRM);
        state.setPendingTask("删除明天的跑步任务");
        mockState(state, ConversationStage.AWAITING_EXECUTION_CONFIRMATION, UserSignal.CONFIRM);
        when(executorAgent.execute(state.getPendingTask())).thenReturn("已删除");

        ChatDispatchResult result = processor.tryHandle(
                1L, "session-1", message, state, UserSignal.CONFIRM, null).orElseThrow();

        assertEquals("已删除", result.aiResult());
        verify(executorAgent).execute("删除明天的跑步任务");
    }

    @Test
    void shouldLoadTheDraftBoundToTheFlowState() {
        String message = "同步到日历";
        AgentFlowState state = state(AgentFlowStateService.AGENT_PLANNER,
                AgentFlowStateService.AGENT_EXECUTOR, AgentFlowStateService.STAGE_WAIT_FEEDBACK);
        state.setPendingDraftId(202L);
        mockState(state, ConversationStage.AWAITING_PLAN_FEEDBACK, UserSignal.SYNC_PLAN);
        when(planDraftService.findPending(1L, "session-1", 202L)).thenReturn(Optional.empty());

        processor.tryHandle(
                1L, "session-1", message, state, UserSignal.SYNC_PLAN, null).orElseThrow();

        verify(planDraftService).findPending(1L, "session-1", 202L);
        verify(planDraftService, never()).findLatestPending(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldGenerateImageOnlyForExplicitImageSignal() {
        String message = "生成一张规划示意图";
        AgentFlowState state = state(AgentFlowStateService.AGENT_PLANNER,
                AgentFlowStateService.AGENT_EXECUTOR, AgentFlowStateService.STAGE_WAIT_FEEDBACK);
        state.setPendingDraftId(303L);
        PlanDraft draft = new PlanDraft();
        draft.setDraftId(303L);
        mockState(state, ConversationStage.AWAITING_PLAN_FEEDBACK, UserSignal.GENERATE_PLAN_IMAGE);
        when(planDraftService.findPending(1L, "session-1", 303L)).thenReturn(Optional.of(draft));
        when(imageAgent.generate(draft)).thenReturn("已生成规划示意图");

        ChatDispatchResult result = processor.tryHandle(
                1L, "session-1", message, state, UserSignal.GENERATE_PLAN_IMAGE, null).orElseThrow();

        assertEquals("PLAN_IMAGE", result.dispatchType());
        assertEquals("已生成规划示意图", result.aiResult());
        verify(imageAgent).generate(draft);
        verify(executorAgent, never()).applyPlan(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldNotGenerateImageForAmbiguousConfirmation() {
        String message = "需要";
        AgentFlowState state = state(AgentFlowStateService.AGENT_PLANNER,
                AgentFlowStateService.AGENT_EXECUTOR, AgentFlowStateService.STAGE_WAIT_FEEDBACK);
        when(flowStateService.isProcessing(state)).thenReturn(false);
        when(flowStateService.resolveStage(state)).thenReturn(ConversationStage.AWAITING_PLAN_FEEDBACK);

        ChatDispatchResult result = processor.tryHandle(
                1L, "session-1", message, state, UserSignal.CONFIRM, null).orElseThrow();

        assertEquals("PENDING_UNKNOWN", result.dispatchType());
        verify(imageAgent, never()).generate(org.mockito.ArgumentMatchers.any());
        verify(executorAgent, never()).applyPlan(org.mockito.ArgumentMatchers.any());
    }

    private void mockState(AgentFlowState state, ConversationStage stage, UserSignal signal) {
        when(flowStateService.isProcessing(state)).thenReturn(false);
        when(flowStateService.resolveStage(state)).thenReturn(stage);
        if (signal != UserSignal.NEW_REQUEST && signal != UserSignal.UNKNOWN) {
            when(flowStateService.claim(state)).thenReturn(true);
        }
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
