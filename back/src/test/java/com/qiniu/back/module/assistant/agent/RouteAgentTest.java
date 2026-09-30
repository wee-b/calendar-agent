package com.qiniu.back.module.assistant.agent;

import com.qiniu.back.module.assistant.domain.vo.RouteDecision;
import com.qiniu.back.module.assistant.statemachine.AgentFlowState;
import com.qiniu.back.module.assistant.statemachine.UserSignal;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RouteAgentTest {
    @Test void pendingTaskCanReceiveAnIndependentQuery() {
        ChatModel model = modelReturning("{\"userSignal\":\"NEW_QUERY\",\"task\":\"查询明天安排\"}");
        AgentFlowState state = new AgentFlowState();
        state.setStage("PLAN");
        state.setPendingTask("复习计划");
        state.setPendingDraftId(18L);
        RouteDecision decision = new RouteAgent(model).route("另外查一下明天", "需要同步吗？", state);
        assertEquals(UserSignal.NEW_QUERY, decision.getUserSignal());
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(request.capture());
        assertTrue(((SystemMessage) request.getValue().messages().get(0)).text().contains("会话阶段：PLAN"));
        assertTrue(((UserMessage) request.getValue().messages().get(1)).singleText().contains("需要同步吗？"));
    }

    @Test void invalidOrLegacySignalsFallBackToUnknownWithoutAuthorizingActions() {
        for (String text : new String[]{"not json", "{}", "{\"userSignal\":\"READY_SINGLE_DAY_ACTION\"}",
                "{\"userSignal\":\"SUPERVISOR\"}"}) {
            assertEquals(UserSignal.UNKNOWN, new RouteAgent(modelReturning(text)).route("继续", null, null).getUserSignal());
        }
    }

    @Test void modelCannotOverrideTheAgentOrNextStage() {
        RouteDecision result = new RouteAgent(modelReturning("""
                {"userSignal":"CONFIRM","nextAgent":"EXECUTOR","nextStage":"CHAT","task":"确认"}
                """)).route("好的", "同步还是生图？", null);
        assertEquals(UserSignal.CONFIRM, result.getUserSignal());
    }

    @Test void explicitSyncAndImageSignalsStayDistinct() {
        for (UserSignal signal : new UserSignal[]{UserSignal.SYNC_PLAN, UserSignal.GENERATE_PLAN_IMAGE}) {
            assertEquals(signal, new RouteAgent(modelReturning("{\"userSignal\":\"" + signal + "\"}"))
                    .route("用户选择", null, null).getUserSignal());
        }
    }

    private ChatModel modelReturning(String json) {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(json)).build());
        return model;
    }
}
