package com.qiniu.back.module.assistant.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/** Shared execution loop used by concrete assistant agents. */
@Slf4j
@Builder
final class AgentRunner {

    private static final int MAX_TOOL_RESULT_LENGTH = 800;

    private final String name;
    private final ChatModel chatModel;
    private final String systemPrompt;
    @Builder.Default
    private final List<ToolSpecification> tools = List.of();
    private final BiFunction<String, String, String> toolExecutor;
    private final double temperature;
    private final int maxRounds;
    private final int maxRetries;
    private final String correctionHint;

    String execute(String task) {
        String result = executeOnce(task);
        for (int attempt = 0; attempt < maxRetries && needsCorrection(result); attempt++) {
            log.info("[{}] 输出异常，第{}次纠正", name, attempt + 1);
            result = executeOnce(task + correctionHint);
        }
        return result;
    }

    private String executeOnce(String task) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(task));

        for (int round = 0; round < maxRounds; round++) {
            ChatRequest.Builder request = ChatRequest.builder().messages(messages).temperature(temperature);
            if (!tools.isEmpty() && round < maxRounds - 1) request.toolSpecifications(tools);

            ChatResponse response = chatModel.chat(request.build());
            AiMessage message = response.aiMessage();
            if (!message.hasToolExecutionRequests()) return message.text();

            messages.add(message);
            for (ToolExecutionRequest toolRequest : message.toolExecutionRequests()) {
                String result = toolExecutor.apply(toolRequest.name(), toolRequest.arguments());
                messages.add(ToolExecutionResultMessage.from(toolRequest, truncate(result)));
            }
        }
        return chatModel.chat(ChatRequest.builder().messages(messages).temperature(temperature).build())
                .aiMessage().text();
    }

    private boolean needsCorrection(String result) {
        if (correctionHint == null || correctionHint.isBlank()) return false;
        if (result == null || result.isBlank()) return true;
        if ("Planner".equals(name)) {
            String value = result.trim();
            return !((value.startsWith("{") && value.endsWith("}"))
                    || (value.startsWith("[") && value.endsWith("]")));
        }
        return result.startsWith("{\"error\"");
    }

    private String truncate(String result) {
        if (result == null) return "";
        if (result.length() <= MAX_TOOL_RESULT_LENGTH) return result;
        return result.substring(0, MAX_TOOL_RESULT_LENGTH) + "...(已截断，原" + result.length() + "字符)";
    }
}
