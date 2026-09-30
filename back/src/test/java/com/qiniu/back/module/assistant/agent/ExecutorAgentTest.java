package com.qiniu.back.module.assistant.agent;

import com.qiniu.back.module.assistant.service.PlanDraftService;
import com.qiniu.back.module.assistant.tool.McpToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExecutorAgentTest {
    @Test void onlySuccessfulWriteToolCallsCountAsExecution() {
        ChatModel model = mock(ChatModel.class);
        McpToolRegistry registry = mock(McpToolRegistry.class);
        when(registry.toLangChain4jSpecifications()).thenReturn(List.of());
        when(model.chat(any(ChatRequest.class))).thenReturn(tool("queryTodoList"), tool("deleteTodo"), answer("已删除"));
        when(registry.executeOnce("queryTodoList", "{}")).thenReturn("待办列表");
        when(registry.executeOnce("deleteTodo", "{}")).thenReturn("已删除");
        ExecutorAgent agent = agent(model, registry);
        ExecutorAgent.ExecutionResult result = agent.executeConfirmed("删除任务");
        assertTrue(result.executed());
        assertEquals("已删除", result.reply());
        verify(registry, never()).execute(anyString(), anyString());
    }

    @Test void modelOnlyAskingForDetailsDoesNotCompleteTheTask() {
        ChatModel model = mock(ChatModel.class);
        McpToolRegistry registry = mock(McpToolRegistry.class);
        when(registry.toLangChain4jSpecifications()).thenReturn(List.of());
        when(model.chat(any(ChatRequest.class))).thenReturn(answer("请说明要删除哪项任务"));
        assertFalse(agent(model, registry).executeConfirmed("删除任务").executed());
        verify(registry, never()).executeOnce(anyString(), anyString());
    }

    @Test void failedWriteIsPropagatedWithoutRetry() {
        ChatModel model = mock(ChatModel.class);
        McpToolRegistry registry = mock(McpToolRegistry.class);
        when(registry.toLangChain4jSpecifications()).thenReturn(List.of());
        when(model.chat(any(ChatRequest.class))).thenReturn(tool("deleteTodo"));
        when(registry.executeOnce("deleteTodo", "{}")).thenThrow(new IllegalStateException("database timeout"));
        assertThrows(IllegalStateException.class, () -> agent(model, registry).executeConfirmed("删除"));
        verify(registry, times(1)).executeOnce("deleteTodo", "{}");
        verify(model, times(1)).chat(any(ChatRequest.class));
    }

    private ExecutorAgent agent(ChatModel model, McpToolRegistry registry) {
        ExecutorAgent agent = new ExecutorAgent(model, registry, mock(PlanDraftService.class));
        agent.init();
        return agent;
    }
    private ChatResponse answer(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }
    private ChatResponse tool(String name) {
        return ChatResponse.builder().aiMessage(AiMessage.from(
                ToolExecutionRequest.builder().id(name).name(name).arguments("{}").build())).build();
    }
}
