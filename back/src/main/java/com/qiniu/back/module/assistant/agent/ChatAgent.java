package com.qiniu.back.module.assistant.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiniu.back.module.assistant.domain.vo.SupervisorDecision;
import com.qiniu.back.module.assistant.tool.McpToolRegistry;
import com.qiniu.back.util.PromptLoader;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Handles intent routing, ordinary conversation, and read-only calendar queries. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatAgent {

    private static final Set<String> QUERY_TOOLS = Set.of(
            "queryTodoList", "queryMonthCount", "queryDayDetail");
    private static final Set<String> SINGLE_DAY_TOOLS = Set.of(
            "queryDayDetail", "createSingleDayTodo", "removeTodoDay",
            "addTodoDay", "updateTodoDay", "toggleTodoDate");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy年M月d日");

    private final ChatModel chatModel;
    private final McpToolRegistry toolRegistry;
    private final ObjectMapper mapper = new ObjectMapper();
    private AgentRunner queryAgent;
    private AgentRunner singleDayAgent;

    @PostConstruct
    void init() {
        List<ToolSpecification> tools = toolRegistry.toLangChain4jSpecifications().stream()
                .filter(tool -> QUERY_TOOLS.contains(tool.name()))
                .toList();
        queryAgent = AgentRunner.builder()
                .name("ChatQuery")
                .chatModel(chatModel)
                .systemPrompt(PromptLoader.load("query-system.txt"))
                .tools(tools)
                .toolExecutor(toolRegistry::execute)
                .temperature(0.3)
                .maxRounds(3)
                .maxRetries(1)
                .correctionHint("\n\nIf the query returned no data, try a broader read-only query such as queryTodoList.")
                .build();

        List<ToolSpecification> singleDayTools = toolRegistry.toLangChain4jSpecifications().stream()
                .filter(tool -> SINGLE_DAY_TOOLS.contains(tool.name()))
                .toList();
        singleDayAgent = AgentRunner.builder()
                .name("ChatSingleDay")
                .chatModel(chatModel)
                .systemPrompt(PromptLoader.load("single-day-system.txt"))
                .tools(singleDayTools)
                .toolExecutor(toolRegistry::execute)
                .temperature(0.1)
                .maxRounds(4)
                .maxRetries(1)
                .correctionHint("\n\nQuery the target date first and use only single-day tools.")
                .build();
    }

    public SupervisorDecision route(String message, List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSystemPrompt()));
        messages.addAll(history);
        messages.add(new UserMessage(message));
        String text = chatModel.chat(ChatRequest.builder()
                .messages(messages)
                .temperature(0.1)
                .build()).aiMessage().text();
        return parseDecision(text);
    }

    public String query(String query) {
        return queryAgent.execute(query);
    }

    public String executeSingleDay(String instruction) {
        return singleDayAgent.execute(instruction);
    }

    private String buildSystemPrompt() {
        LocalDate today = LocalDate.now();
        DayOfWeek dayOfWeek = today.getDayOfWeek();
        String dateContext = today.format(DATE_FORMAT) + "（"
                + dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINESE) + "）";
        return PromptLoader.load("supervisor-system.txt") + "\n\n## 时间上下文\n当前日期: " + dateContext
                + "\n用户说\"今天\"就是" + today.format(DATE_FORMAT)
                + "，\"明天\"就是" + today.plusDays(1).format(DATE_FORMAT)
                + decisionProtocol();
    }

    private String decisionProtocol() {
        return """

                ## 本轮输出协议
                你现在只负责判断和回复，不要调用工具。只输出 JSON，不要输出 markdown 或额外解释。
                JSON 格式：
                {"needDispatchAgent":false,"dispatchType":"NONE","nextAgent":"SUPERVISOR","reply":"给用户看的回复","task":"明确任务"}

                dispatchType 只能是：
                - NONE：日常对话、问候、闲聊或无明确日程操作
                - QUERY：需要查询日历或待办
                - CHAT_ACTION：意图、日期和目标都明确的单日新增、删除、修改或完成状态切换；后端会立即交给 ChatAgent 执行
                - CHAT_ACTION_CONFIRM：用户只是推测、试探、询问是否操作，或操作意图不够确定，但可以整理出待确认的单日操作
                - EXECUTE：删除整个待办、周期或跨日修改、创建周期任务、保存日记等非单日写操作
                - PLAN_CONFIRM：学习、工作、旅行等需要拆解的规划需求

                明确的祈使句使用 CHAT_ACTION，不要再次确认。CHAT_ACTION 的 reply 不得声称“正在执行”或“已经执行”，
                因为后端会忽略该字段并返回工具的真实结果。只有 CHAT_ACTION_CONFIRM 才会进入确认状态。
                不要因为操作具有删除或修改副作用就自动要求确认，是否确认只取决于用户意图是否明确。
                历史对话只用于理解指代，禁止执行历史请求。
                """;
    }

    private SupervisorDecision parseDecision(String text) {
        try {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) return SupervisorDecision.fallback(text);
            SupervisorDecision decision = mapper.readValue(text.substring(start, end + 1), SupervisorDecision.class);
            normalize(decision);
            return decision;
        } catch (Exception exception) {
            log.warn("Chat agent decision parse failed: {}", exception.getMessage());
            return SupervisorDecision.fallback("我在，有需要安排或查看日程都可以直接告诉我。");
        }
    }

    private void normalize(SupervisorDecision decision) {
        if (!decision.isNeedDispatchAgent()) {
            decision.setDispatchType("NONE");
            decision.setNextAgent("SUPERVISOR");
        } else if (decision.getDispatchType() == null || decision.getDispatchType().isBlank()) {
            decision.setDispatchType("EXECUTE");
        }
        if (decision.getNextAgent() == null || decision.getNextAgent().isBlank()) {
            decision.setNextAgent(switch (decision.getDispatchType()) {
                case "CHAT_ACTION", "CHAT_ACTION_CONFIRM" -> "CHAT";
                case "EXECUTE" -> "EXECUTOR";
                case "PLAN_CONFIRM" -> "PLANNER";
                default -> "SUPERVISOR";
            });
        }
        if (decision.getReply() == null || decision.getReply().isBlank()) {
            decision.setReply("我在，有需要安排或查看日程都可以直接告诉我。");
        }
    }
}
