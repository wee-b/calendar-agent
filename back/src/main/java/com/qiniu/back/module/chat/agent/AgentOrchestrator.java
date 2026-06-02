package com.qiniu.back.module.chat.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import com.qiniu.back.util.PromptLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Supervisor 主控——接收用户消息，通过调用子 Agent 工具完成多步骤调度，最终汇总回复。
 */
@Slf4j
@Component
public class AgentOrchestrator {

    private static final int MAX_SUPERVISOR_ROUNDS = 10;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy年M月d日");

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private StreamingChatModel streamingChatModel;

    @Autowired
    private SupervisorTools supervisorTools;

    /**
     * 非流式编排——Supervisor 调度子 Agent 完成用户请求，返回最终文本。
     */
    public String orchestrate(String userMessage, List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSupervisorPrompt()));
        messages.addAll(history);
        messages.add(new UserMessage(userMessage));

        return chatWithSupervisor(messages);
    }

    /**
     * 流式编排（需调用方负责异步线程和用户上下文）——Supervisor 最终回复以 SSE 流式输出；
     * 子 Agent 内部调用不流式。
     * <p>
     * 调用方应在独立线程中调用此方法，并提前设置 LoginUserContext / ThreadLocal。
     *
     * @param emitter   由调用方创建的 SseEmitter
     * @param onComplete 流结束后回调，参数为完整文本，用于落库
     */
    public void orchestrateStreamInternal(SseEmitter emitter, String userMessage,
                                          List<ChatMessage> history, Consumer<String> onComplete) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSupervisorPrompt()));
        messages.addAll(history);
        messages.add(new UserMessage(userMessage));

        List<ToolSpecification> toolSpecs = supervisorTools.getSupervisorSpecs();
        streamWithSupervisor(emitter, messages, toolSpecs, onComplete);
    }

    // ==================== 非流式 Supervisor 循环 ====================

    private String chatWithSupervisor(List<ChatMessage> messages) {
        List<ToolSpecification> toolSpecs = supervisorTools.getSupervisorSpecs();

        for (int round = 0; round < MAX_SUPERVISOR_ROUNDS; round++) {
            ChatRequest.Builder builder = ChatRequest.builder()
                    .messages(messages)
                    .temperature(0.7);

            if (round < MAX_SUPERVISOR_ROUNDS - 1) {
                builder.toolSpecifications(toolSpecs);
            }

            ChatResponse response = chatModel.chat(builder.build());
            AiMessage aiMsg = response.aiMessage();

            if (!aiMsg.hasToolExecutionRequests()) {
                return aiMsg.text();
            }

            messages.add(aiMsg);
            for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
                log.info("[Supervisor] 调度: {} -> args length: {}", req.name(), req.arguments().length());
                String result = supervisorTools.executeSupervisorTool(req.name(), req.arguments());
                messages.add(ToolExecutionResultMessage.from(req, result));
            }
        }

        ChatResponse response = chatModel.chat(ChatRequest.builder()
                .messages(messages).temperature(0.7).build());
        return response.aiMessage().text();
    }

    // ==================== 流式 Supervisor 循环 ====================

    private void streamWithSupervisor(SseEmitter emitter, List<ChatMessage> messages,
                                      List<ToolSpecification> toolSpecs,
                                      Consumer<String> onComplete) {
        try {
            for (int round = 0; round < MAX_SUPERVISOR_ROUNDS; round++) {
                ChatRequest.Builder builder = ChatRequest.builder()
                        .messages(messages)
                        .temperature(0.7);

                if (round < MAX_SUPERVISOR_ROUNDS - 1) {
                    builder.toolSpecifications(toolSpecs);
                }

                CompletableFuture<ChatResponse> future = new CompletableFuture<>();
                StringBuilder fullText = new StringBuilder();

                streamingChatModel.chat(builder.build(), new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                        fullText.append(partialResponse);
                        try {
                            emitter.send(SseEmitter.event().data(partialResponse));
                        } catch (IOException e) {
                            future.completeExceptionally(e);
                        }
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        future.complete(completeResponse);
                    }

                    @Override
                    public void onError(Throwable error) {
                        future.completeExceptionally(error);
                    }
                });

                ChatResponse response;
                try {
                    response = future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    emitter.complete();
                    return;
                } catch (ExecutionException e) {
                    emitter.completeWithError(e.getCause());
                    return;
                }

                AiMessage aiMsg = response.aiMessage();

                if (!aiMsg.hasToolExecutionRequests()) {
                    emitter.complete();
                    if (onComplete != null) onComplete.accept(fullText.toString());
                    return;
                }

                messages.add(aiMsg);
                for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
                    log.info("[Supervisor] 调度: {} -> args length: {}", req.name(), req.arguments().length());
                    String result = supervisorTools.executeSupervisorTool(req.name(), req.arguments());
                    messages.add(ToolExecutionResultMessage.from(req, result));
                }
            }
            emitter.complete();
        } catch (Exception e) {
            log.error("Supervisor 流式循环异常", e);
            emitter.completeWithError(e);
        }
    }

    // ==================== 提示词构建 ====================

    private String buildSupervisorPrompt() {
        LocalDate today = LocalDate.now();
        DayOfWeek dow = today.getDayOfWeek();
        String weekDayCn = dow.getDisplayName(TextStyle.FULL, Locale.CHINESE);
        String todayStr = today.format(DATE_FMT) + "（" + weekDayCn + "）";

        return PromptLoader.load("supervisor-system.txt")
                + "\n\n## 时间上下文\n当前日期: " + todayStr
                + "\n用户说\"今天\"就是" + today.format(DATE_FMT)
                + "，\"明天\"就是" + today.plusDays(1).format(DATE_FMT) + "，以此类推。";
    }
}
