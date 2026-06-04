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

/**
 * 子 Agent — ChatModel + SystemPrompt + 工具集 的封装。
 * <p>
 * 每个子 Agent 独立运行自己的工具调用循环，对外暴露为 {@code execute(task) -> 结果文本}。
 * 不直接持有工具执行逻辑，通过 {@code toolExecutor} 函数注入。
 */
@Slf4j
public class SubAgent {

    private static final int MAX_ROUNDS = 5;

    private final String name;
    private final ChatModel chatModel;
    private final String systemPrompt;
    private final double temperature;
    private final List<ToolSpecification> tools;
    /** (toolName, argumentsJson) -> resultText */
    private final BiFunction<String, String, String> toolExecutor;

    public SubAgent(String name, ChatModel chatModel, String systemPrompt,
                    double temperature,List<ToolSpecification> tools,
                    BiFunction<String, String, String> toolExecutor) {
        this.name = name;
        this.chatModel = chatModel;
        this.systemPrompt = systemPrompt;
        this.temperature = temperature;
        this.tools = tools;
        this.toolExecutor = toolExecutor;
    }

    /**
     * 执行子 Agent 任务——输入任务描述，返回 LLM 最终文本结果。
     */
    public String execute(String task) {
        log.info("[{}] 接收任务: {}", name, task.length() > 120 ? task.substring(0, 120) + "..." : task);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(task));

        for (int round = 0; round < MAX_ROUNDS; round++) {
            ChatRequest.Builder builder = ChatRequest.builder()
                    .messages(messages)
                    .temperature(this.temperature);

            if (!tools.isEmpty() && round < MAX_ROUNDS - 1) {
                builder.toolSpecifications(tools);
            }

            ChatResponse response = chatModel.chat(builder.build());
            AiMessage aiMsg = response.aiMessage();

            if (!aiMsg.hasToolExecutionRequests()) {
                String result = aiMsg.text();
                log.info("[{}] 完成，输出 {} 字符", name, result != null ? result.length() : 0);
                return result;
            }

            messages.add(aiMsg);
            for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
                log.info("[{}] 调用工具: {} args: {}", name, req.name(), req.arguments());
                String toolResult = toolExecutor.apply(req.name(), req.arguments());
                messages.add(ToolExecutionResultMessage.from(req, toolResult));
            }
        }

        ChatResponse response = chatModel.chat(ChatRequest.builder()
                .messages(messages).temperature(0.7).build());
        return response.aiMessage().text();
    }
}
