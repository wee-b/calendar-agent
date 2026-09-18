package com.qiniu.back.module.assistant.agent;

import com.qiniu.back.module.assistant.domain.vo.SupervisorDecision;
import com.qiniu.back.module.assistant.statemachine.AgentFlowState;
import com.qiniu.back.module.assistant.statemachine.AgentFlowStateService;
import com.qiniu.back.module.assistant.statemachine.UserSignal;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouteAgentTest {

    @Test
    void preservesStructuredModificationSignalForPendingFlow() {
        ChatModel model = modelReturning("""
                {"userSignal":"MODIFY","needDispatchAgent":false,"dispatchType":"NONE",
                 "nextAgent":"PLANNER","reply":"","task":"补充当前规划"}
                """);
        RouteAgent agent = new RouteAgent(model);
        AgentFlowState state = new AgentFlowState();
        state.setStage(AgentFlowStateService.STAGE_WAIT_CONFIRM);
        state.setCurrentAgent(AgentFlowStateService.AGENT_PLANNER);
        state.setNextAgent(AgentFlowStateService.AGENT_PLANNER);
        state.setPendingTask("制定学习计划");

        SupervisorDecision decision = agent.route("补充说明", "需要我现在开始规划吗？", List.of(), state);

        assertEquals(UserSignal.MODIFY, decision.getUserSignal());
    }

    @Test
    void keepsShortSyncAgreementAsConfirm() {
        ChatModel model = modelReturning("""
                {"userSignal":"CONFIRM","needDispatchAgent":false,"dispatchType":"NONE",
                 "nextAgent":"EXECUTOR","reply":"","task":"同步当前规划"}
                """);
        RouteAgent agent = new RouteAgent(model);
        AgentFlowState state = new AgentFlowState();
        state.setStage(AgentFlowStateService.STAGE_WAIT_FEEDBACK);
        state.setCurrentAgent(AgentFlowStateService.AGENT_PLANNER);
        state.setNextAgent(AgentFlowStateService.AGENT_EXECUTOR);
        state.setPendingTask("中秋节突击计算机二级");

        String previous = "规划草稿\n\n需要同步到日历中吗？";
        SupervisorDecision decision = agent.route("需要", previous, List.of(), state);

        assertEquals(UserSignal.CONFIRM, decision.getUserSignal());
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(request.capture());
        UserMessage turn = (UserMessage) request.getValue().messages().get(request.getValue().messages().size() - 1);
        assertTrue(turn.singleText().contains("需要同步到日历中吗？"));
        assertTrue(turn.singleText().contains("本轮用户消息：\n需要"));
    }

    @Test
    void derivesReadySignalFromDispatchTypeWhenModelOmitsSignal() {
        ChatModel model = modelReturning("""
                {"needDispatchAgent":true,"dispatchType":"QUERY",
                 "nextAgent":"SUPERVISOR","reply":"","task":"查询明天安排"}
                """);
        RouteAgent agent = new RouteAgent(model);

        SupervisorDecision decision = agent.route("查看明天", null, List.of(), null);

        assertEquals(UserSignal.READY_QUERY, decision.getUserSignal());
    }

    private ChatModel modelReturning(String json) {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from(json))
                .build());
        return model;
    }
}
