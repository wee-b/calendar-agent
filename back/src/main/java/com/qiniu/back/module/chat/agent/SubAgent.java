package com.qiniu.back.module.chat.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

@Slf4j
public class SubAgent {

    private static final int DEFAULT_MAX_ROUNDS = 5;
    private static final double DEFAULT_TEMPERATURE = 0.7;
    private static final int MAX_TOOL_RESULT_LEN = 800;

    private final String name;
    private final ChatModel chatModel;
    private final String systemPrompt;
    private final List<ToolSpecification> tools;
    private final BiFunction<String, String, String> toolExecutor;
    private final double temperature;
    private final int maxRounds;

    private SubAgent(Builder builder) {
        this.name = builder.name;
        this.chatModel = builder.chatModel;
        this.systemPrompt = builder.systemPrompt;
        this.tools = builder.tools;
        this.toolExecutor = builder.toolExecutor;
        this.temperature = builder.temperature;
        this.maxRounds = builder.maxRounds;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String execute(String task) {
        log.info("[{}] 接收任务: {}", name,
                task.length() > 120 ? task.substring(0, 120) + "..." : task);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(task));

        for (int round = 0; round < maxRounds; round++) {
            ChatRequest.Builder reqBuilder = ChatRequest.builder()
                    .messages(messages)
                    .temperature(temperature);

            if (!tools.isEmpty() && round < maxRounds - 1) {
                reqBuilder.toolSpecifications(tools);
            }

            ChatResponse response = chatModel.chat(reqBuilder.build());
            AiMessage aiMsg = response.aiMessage();

            if (!aiMsg.hasToolExecutionRequests()) {
                String result = aiMsg.text();
                log.info("[{}] 完成，输出 {} 字符", name,
                        result != null ? result.length() : 0);
                return result;
            }

            messages.add(aiMsg);
            for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
                log.info("[{}] 调用工具: {} args: {}", name, req.name(), req.arguments());
                String raw = toolExecutor.apply(req.name(), req.arguments());
                messages.add(ToolExecutionResultMessage.from(req, truncate(raw)));
            }
        }

        ChatResponse response = chatModel.chat(ChatRequest.builder()
                .messages(messages).temperature(temperature).build());
        return response.aiMessage().text();
    }

    private static String truncate(String result) {
        if (result == null) return "";
        if (result.length() <= MAX_TOOL_RESULT_LEN) return result;
        return result.substring(0, MAX_TOOL_RESULT_LEN)
                + "...(已截断，原" + result.length() + "字符)";
    }

    // ==================== Builder ====================

    public static class Builder {
        private String name;
        private ChatModel chatModel;
        private String systemPrompt;
        private List<ToolSpecification> tools = List.of();
        private BiFunction<String, String, String> toolExecutor;
        private double temperature = DEFAULT_TEMPERATURE;
        private int maxRounds = DEFAULT_MAX_ROUNDS;

        public Builder name(String name) { this.name = name; return this; }
        public Builder chatModel(ChatModel chatModel) { this.chatModel = chatModel; return this; }
        public Builder systemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; return this; }
        public Builder tools(List<ToolSpecification> tools) { this.tools = tools; return this; }
        public Builder toolExecutor(BiFunction<String, String, String> f) { this.toolExecutor = f; return this; }
        public Builder temperature(double t) { this.temperature = t; return this; }
        public Builder maxRounds(int n) { this.maxRounds = n; return this; }

        public SubAgent build() {
            return new SubAgent(this);
        }
    }
}
