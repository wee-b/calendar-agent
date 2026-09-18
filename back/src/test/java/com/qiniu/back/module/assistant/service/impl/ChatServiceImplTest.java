package com.qiniu.back.module.assistant.service.impl;

import com.qiniu.back.module.assistant.agent.ChatAgent;
import com.qiniu.back.module.assistant.domain.result.ChatDispatchResult;
import com.qiniu.back.module.assistant.domain.vo.SupervisorDecision;
import com.qiniu.back.module.assistant.service.ChatContextSummaryService;
import com.qiniu.back.module.assistant.service.ProgressReporter;
import com.qiniu.back.module.assistant.statemachine.AgentFlowState;
import com.qiniu.back.module.assistant.statemachine.AgentFlowStateService;
import com.qiniu.back.module.assistant.statemachine.ChatTransitionTable;
import com.qiniu.back.module.assistant.statemachine.ConversationStage;
import com.qiniu.back.module.assistant.statemachine.ExistingFlowStateProcessor;
import com.qiniu.back.module.assistant.statemachine.UserSignal;
import com.qiniu.back.module.assistant.statemachine.UserSignalResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    @Mock private ChatAgent chatAgent;
    @Mock private AgentFlowStateService flowStateService;
    @Mock private UserSignalResolver signalResolver;

    private ChatServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ChatServiceImpl(flowStateProcessor, contextSummaryService, chatAgent,
                flowStateService, new ChatTransitionTable(), signalResolver, new ProgressReporter());
    }

    @Test
    void explicitSingleDayActionExecutesImmediatelyThroughChatAgent() {
        String message = "明确的单日删除指令";
        stubReady(message);
        when(chatAgent.route(message, List.of())).thenReturn(decision("CHAT_ACTION", "删除指定日期的目标"));
        when(chatAgent.executeSingleDay("删除指定日期的目标")).thenReturn("操作成功");

        ChatDispatchResult result = service.process(1L, "session-1", message, 10L);

        assertEquals("操作成功", result.aiResult());
        assertEquals("CHAT_ACTION", result.dispatchType());
        assertEquals(AgentFlowStateService.STAGE_IDLE, result.flowStage());
        verify(flowStateService, never()).waitChatConfirm(
                org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString());
    }

    @Test
    void uncertainSingleDayActionUsesARealConfirmationQuestion() {
        String message = "不确定的单日操作表达";
        String task = "删除指定日期的目标";
        stubReady(message);
        SupervisorDecision decision = decision("CHAT_ACTION_CONFIRM", task);
        decision.setReply("正在处理");
        when(chatAgent.route(message, List.of())).thenReturn(decision);
        when(flowStateService.waitChatConfirm(1L, "session-1", task)).thenReturn(new AgentFlowState());

        ChatDispatchResult result = service.process(1L, "session-1", message, 10L);

        assertEquals("CHAT_ACTION_CONFIRM", result.dispatchType());
        assertTrue(result.aiResult().contains("需要我执行"));
        assertTrue(!result.aiResult().contains("正在处理"));
        verify(chatAgent, never()).executeSingleDay(anyString());
    }

    private void stubReady(String message) {
        when(flowStateProcessor.tryHandle(1L, "session-1", message, null)).thenReturn(Optional.empty());
        when(signalResolver.resolve(ConversationStage.READY_FOR_INPUT, null, message))
                .thenReturn(UserSignal.NEW_MESSAGE);
        when(contextSummaryService.buildCompressedReadonlyHistory(1L, "session-1", 10L))
                .thenReturn(List.of());
    }

    private SupervisorDecision decision(String type, String task) {
        SupervisorDecision decision = new SupervisorDecision();
        decision.setNeedDispatchAgent(true);
        decision.setDispatchType(type);
        decision.setNextAgent(AgentFlowStateService.AGENT_CHAT);
        decision.setTask(task);
        return decision;
    }
}
