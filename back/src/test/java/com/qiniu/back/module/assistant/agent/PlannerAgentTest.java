package com.qiniu.back.module.assistant.agent;

import com.qiniu.back.domain.ErrorCode;
import com.qiniu.back.exception.BusinessException;
import com.qiniu.back.module.assistant.rag.RagService;
import com.qiniu.back.module.assistant.service.PlanDraftService;
import com.qiniu.back.module.memory.service.UserMemoryService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlannerAgentTest {

    @Test
    void retriesWhenGeneratedDatesUseAPastYear() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
                response("{\"goal\":\"计算机二级\",\"todos\":[]}"),
                response("{\"goal\":\"计算机二级\",\"todos\":[]}"));
        PlanDraftService planDraftService = mock(PlanDraftService.class);
        when(planDraftService.savePendingDraft(any(), any(), anyString(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.BAD_REQUEST, "Plan draft year is earlier than current year"))
                .thenReturn(11L);
        when(planDraftService.buildPreviewReply(11L, "{\"goal\":\"计算机二级\",\"todos\":[]}"))
                .thenReturn("规划预览");
        RagService ragService = mock(RagService.class);
        when(ragService.search(anyString())).thenReturn(List.of());

        PlannerAgent agent = new PlannerAgent(model, planDraftService, mock(UserMemoryService.class), ragService);
        agent.init();

        PlannerAgent.GeneratedPlan plan = agent.generateDraft("中秋节三天突击计算机二级");

        assertEquals("规划预览", plan.preview());
        verify(planDraftService, times(2)).savePendingDraft(any(), any(), anyString(), anyString());
        verify(model, times(2)).chat(any(ChatRequest.class));
    }

    private ChatResponse response(String json) {
        return ChatResponse.builder().aiMessage(AiMessage.from(json)).build();
    }
}
