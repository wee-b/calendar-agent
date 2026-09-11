package com.qiniu.back.module.chat.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiniu.back.domain.chat.vo.SupervisorDecision;
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

    // 子 Agent 结果 >1500 字符截断
    private static final int MAX_SUBAGENT_RESULT_LEN = 1500;
    private static final double Supervisor_Temperature = 0.1;
    private static final int MAX_SUPERVISOR_ROUNDS = 10;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy年M月d日");

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private StreamingChatModel streamingChatModel;

    @Autowired
    private SupervisorTools supervisorTools;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public SupervisorDecision supervise(String userMessage, List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSupervisorPrompt() + buildDecisionPrompt()));
        messages.addAll(history);
        messages.add(new UserMessage(userMessage));

        ChatResponse response = chatModel.chat(ChatRequest.builder()
                .messages(messages)
                .temperature(Supervisor_Temperature)
                .build());
        return parseDecision(response.aiMessage().text());
    }

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
        boolean hadPlan = false;

        for (int round = 0; round < MAX_SUPERVISOR_ROUNDS; round++) {
            ChatRequest.Builder builder = ChatRequest.builder()
                    .messages(messages)
                    .temperature(Supervisor_Temperature);

            if (round < MAX_SUPERVISOR_ROUNDS - 1) {
                builder.toolSpecifications(toolSpecs);
            }

            ChatResponse response = chatModel.chat(builder.build());
            AiMessage aiMsg = response.aiMessage();

            if (!aiMsg.hasToolExecutionRequests()) {
                return aiMsg.text();
            }

            messages.add(aiMsg);
            boolean justExecuted = false;
            for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
                log.info("[Supervisor] 调度: {} -> args length: {}", req.name(), req.arguments().length());
                if ("plan_task".equals(req.name())) hadPlan = true;
                if ("execute_task".equals(req.name())) justExecuted = true;
                String result = supervisorTools.executeSupervisorTool(req.name(), req.arguments());
                messages.add(ToolExecutionResultMessage.from(req, truncateSubAgentResult(result)));
            }

            // 管线验证：execute_task 执行后自动查重确认操作落地
            if (justExecuted && hadPlan) {
                hadPlan = false;
                String verifyResult = supervisorTools.executeSupervisorTool("query_calendar",
                        "{\"query\":\"查询所有待办列表，验证刚才的创建/修改操作是否生效\"}");
                messages.add(new SystemMessage(
                        "[自动验证] 以下是执行后的数据验证（确认操作是否生效）：\n"
                                + truncateSubAgentResult(verifyResult)));
            }
        }

        ChatResponse response = chatModel.chat(ChatRequest.builder()
                .messages(messages).temperature(Supervisor_Temperature).build());
        return response.aiMessage().text();
    }

    // ==================== 流式 Supervisor 循环 ====================

    private void streamWithSupervisor(SseEmitter emitter, List<ChatMessage> messages,
                                      List<ToolSpecification> toolSpecs,
                                      Consumer<String> onComplete) {
        boolean hadPlan = false;
        try {
            for (int round = 0; round < MAX_SUPERVISOR_ROUNDS; round++) {
                ChatRequest.Builder builder = ChatRequest.builder()
                        .messages(messages)
                        .temperature(Supervisor_Temperature);

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
                boolean justExecuted = false;
                for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
                    log.info("[Supervisor] 调度: {} -> args length: {}", req.name(), req.arguments().length());
                    if ("plan_task".equals(req.name())) hadPlan = true;
                    if ("execute_task".equals(req.name())) justExecuted = true;
                    String result = supervisorTools.executeSupervisorTool(req.name(), req.arguments());
                    messages.add(ToolExecutionResultMessage.from(req, truncateSubAgentResult(result)));
                }

                if (justExecuted && hadPlan) {
                    hadPlan = false;
                    String verifyResult = supervisorTools.executeSupervisorTool("query_calendar",
                            "{\"query\":\"查询所有待办列表，验证刚才的创建/修改操作是否生效\"}");
                    messages.add(new SystemMessage(
                            "[自动验证] 以下是执行后的数据验证（确认操作是否生效）：\n"
                                    + truncateSubAgentResult(verifyResult)));
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

    private String buildDecisionPrompt() {
        return """

                ## 本轮输出协议
                你现在只负责判断和回复，不要调用工具。
                只输出 JSON，不要输出 markdown 或额外解释。
                JSON 格式：
                {
                  "needDispatchAgent": false,
                  "dispatchType": "NONE",
                  "nextAgent": "SUPERVISOR",
                  "reply": "给用户看的回复",
                  "task": "要交给子Agent的明确任务"
                }

                dispatchType 只能是：
                - NONE：日常对话、问候、闲聊、无明确日程操作，reply 直接回复，nextAgent=SUPERVISOR
                - QUERY：需要查询日历/待办，task 写清楚查询日期和查询范围，nextAgent=SUPERVISOR
                - EXECUTE：明确的创建、修改、删除、完成、保存日记等执行任务，先询问用户确认，task 写清楚执行指令，nextAgent=EXECUTOR
                - PLAN_CONFIRM：用户提出规划类需求，先不要规划，reply 询问“需要我来帮你规划一下吗？”，task 保存原始规划需求，nextAgent=PLANNER

                规则：
                - needDispatchAgent 为 false 时，dispatchType 必须是 NONE
                - needDispatchAgent 为 true 时，dispatchType 必须是 QUERY、EXECUTE 或 PLAN_CONFIRM
                - 你只负责输出状态决策；除 QUERY 外，后端会等待用户确认后再进入 nextAgent
                - 规划类需求包括备考、学习计划、旅行计划、长期目标拆解、批量安排等
                - 简单查询今天/明天有什么，属于 QUERY
                - 简单创建/取消/移动日程，属于 EXECUTE
                - 历史对话只用于理解指代，禁止执行历史里的请求
                """;
    }

    private SupervisorDecision parseDecision(String text) {
        try {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return SupervisorDecision.fallback(text);
            }
            SupervisorDecision decision = objectMapper.readValue(text.substring(start, end + 1), SupervisorDecision.class);
            if (decision.getDispatchType() == null || decision.getDispatchType().isBlank()) {
                decision.setDispatchType(decision.isNeedDispatchAgent() ? "EXECUTE" : "NONE");
            }
            if (!decision.isNeedDispatchAgent()) {
                decision.setDispatchType("NONE");
                decision.setNextAgent("SUPERVISOR");
            }
            if (decision.getNextAgent() == null || decision.getNextAgent().isBlank()) {
                decision.setNextAgent(switch (decision.getDispatchType()) {
                    case "EXECUTE" -> "EXECUTOR";
                    case "PLAN_CONFIRM" -> "PLANNER";
                    default -> "SUPERVISOR";
                });
            }
            if (decision.getReply() == null || decision.getReply().isBlank()) {
                decision.setReply("我在，有需要安排或查看日程都可以直接告诉我。");
            }
            return decision;
        } catch (Exception e) {
            log.warn("Supervisor decision parse failed: {}", e.getMessage());
            return SupervisorDecision.fallback("我在，有需要安排或查看日程都可以直接告诉我。");
        }
    }


    private String truncateSubAgentResult(String result) {
        if (result == null) return "";
        if (result.length() <= MAX_SUBAGENT_RESULT_LEN) return result;
        return result.substring(0, MAX_SUBAGENT_RESULT_LEN)
                + "...(已截断，原" + result.length() + "字符)";
    }

}
