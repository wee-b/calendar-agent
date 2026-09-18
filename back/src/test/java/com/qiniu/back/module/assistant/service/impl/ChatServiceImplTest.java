package com.qiniu.back.module.assistant.service.impl;

import com.qiniu.back.module.assistant.agent.ChatAgent;
import com.qiniu.back.module.assistant.agent.ExecutorAgent;
import com.qiniu.back.module.assistant.agent.RouteAgent;
import com.qiniu.back.module.assistant.domain.result.ChatDispatchResult;
import com.qiniu.back.module.assistant.domain.vo.RouteDecision;
import com.qiniu.back.module.assistant.service.ChatContextSummaryService;
import com.qiniu.back.module.assistant.service.ChatDialogueService;
import com.qiniu.back.module.assistant.service.ProgressReporter;
import com.qiniu.back.module.assistant.statemachine.AgentFlowState;
import com.qiniu.back.module.assistant.statemachine.AgentFlowStateService;
import com.qiniu.back.module.assistant.statemachine.ChatTransitionTable;
import com.qiniu.back.module.assistant.statemachine.ExistingFlowStateProcessor;
import com.qiniu.back.module.assistant.statemachine.UserSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceImplTest {

    @Mock private ExistingFlowStateProcessor flowStateProcessor;
    @Mock private ChatContextSummaryService contextSummaryService;
    @Mock private ChatDialogueService chatDialogueService;
    @Mock private ChatAgent chatAgent;
    @Mock private ExecutorAgent executorAgent;
    @Mock private RouteAgent routeAgent;
    @Mock private AgentFlowStateService flowStateService;

    private ChatServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ChatServiceImpl(flowStateProcessor, contextSummaryService, chatDialogueService,
                chatAgent, executorAgent, routeAgent, flowStateService, new ChatTransitionTable(),
                new ProgressReporter());
    }

    @Test
    void explicitSingleDayActionExecutesImmediatelyThroughExecutor() {
        String message = "明确的单日删除指令";
        stubReady(message, decision(message, UserSignal.READY_SINGLE_DAY_ACTION));
        when(executorAgent.execute(message)).thenReturn("操作成功");
        List<String> progress = new ArrayList<>();

        ChatDispatchResult result = service.process(1L, "session-1", message, 10L, progress::add);

        assertEquals("操作成功", result.aiResult());
        assertEquals("CHAT_ACTION", result.dispatchType());
        assertEquals(AgentFlowStateService.STAGE_IDLE, result.flowStage());
        verify(flowStateService, never()).waitExecutorConfirm(
                org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString());
        verify(routeAgent).route(message, null, List.of(), null);
        assertTrue(progress.stream().anyMatch(item -> item.contains("结合上一轮回复生成结构化路由事件")));
    }

    @Test
    void uncertainSingleDayActionUsesARealConfirmationQuestion() {
        String message = "不确定的单日操作表达";
        String task = "删除指定日期的目标";
        RouteDecision decision = decision(task, UserSignal.READY_SINGLE_DAY_CONFIRM);
        decision.setReply("正在处理");
        stubReady(message, decision);
        when(flowStateService.waitExecutorConfirm(1L, "session-1", task)).thenReturn(new AgentFlowState());
        List<String> progress = new ArrayList<>();

        ChatDispatchResult result = service.process(1L, "session-1", message, 10L, progress::add);

        assertEquals("CHAT_ACTION_CONFIRM", result.dispatchType());
        assertTrue(result.aiResult().contains("需要我执行"));
        assertTrue(!result.aiResult().contains("正在处理"));
        verify(executorAgent, never()).execute(anyString());
        assertTrue(progress.stream().anyMatch(item -> item.contains("状态机消费结构化路由事件")));
    }

    @Test
    void pendingSupplementIsRoutedBeforeStateMachineConsumesIt() {
        String message = "补充当前任务的信息";
        AgentFlowState state = new AgentFlowState();
        state.setStage(AgentFlowStateService.STAGE_WAIT_CONFIRM);
        RouteDecision decision = decision(message, UserSignal.MODIFY);
        ChatDispatchResult handled = new ChatDispatchResult(
                "已补充", false, "REFINE", AgentFlowStateService.AGENT_PLANNER,
                AgentFlowStateService.AGENT_PLANNER, AgentFlowStateService.STAGE_WAIT_CONFIRM);
        when(flowStateService.get(1L, "session-1")).thenReturn(Optional.of(state));
        when(chatDialogueService.findLatestAssistantReply(1L, "session-1", 10L))
                .thenReturn("需要同步到日历中吗？");
        when(contextSummaryService.buildCompressedReadonlyHistory(1L, "session-1", 10L)
                ).thenReturn(List.of());
        when(routeAgent.route(message, "需要同步到日历中吗？", List.of(), state)).thenReturn(decision);
        when(flowStateProcessor.tryHandle(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.eq(message),
                org.mockito.ArgumentMatchers.same(state),
                org.mockito.ArgumentMatchers.eq(UserSignal.MODIFY),
                org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(handled));
        List<String> progress = new ArrayList<>();

        ChatDispatchResult result = service.process(
                1L, "session-1", message, 10L, progress::add);

        assertEquals("REFINE", result.dispatchType());
        int routeIndex = indexOf(progress, "结合上一轮回复生成结构化路由事件");
        int stateIndex = indexOf(progress, "状态机消费结构化路由事件");
        assertTrue(routeIndex >= 0 && stateIndex > routeIndex);
    }

    private void stubReady(String message, RouteDecision decision) {
        when(flowStateService.get(1L, "session-1")).thenReturn(Optional.empty());
        when(chatDialogueService.findLatestAssistantReply(1L, "session-1", 10L)).thenReturn(null);
        when(contextSummaryService.buildCompressedReadonlyHistory(1L, "session-1", 10L))
                .thenReturn(List.of());
        when(routeAgent.route(message, null, List.of(), null)).thenReturn(decision);
        when(flowStateProcessor.tryHandle(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.eq(message),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(decision.getUserSignal()),
                org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
    }

    private RouteDecision decision(String task, UserSignal signal) {
        RouteDecision decision = new RouteDecision();
        decision.setTask(task);
        decision.setUserSignal(signal);
        return decision;
    }

    private int indexOf(List<String> progress, String text) {
        for (int index = 0; index < progress.size(); index++) {
            if (progress.get(index).contains(text)) return index;
        }
        return -1;
    }
}
