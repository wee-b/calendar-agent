package com.qiniu.back.module.assistant.agent;

import com.qiniu.back.module.assistant.tool.McpToolRegistry;
import com.qiniu.back.util.PromptLoader;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Executes calendar queries and high-confidence single-day operations. */
@Component
@RequiredArgsConstructor
public class ChatAgent {

    private static final Set<String> QUERY_TOOLS = Set.of(
            "queryTodoList", "queryMonthCount", "queryDayDetail");
    private final ChatModel chatModel;
    private final McpToolRegistry toolRegistry;
    private AgentRunner queryAgent;

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
    }

    public String query(String query) {
        return queryAgent.execute(withDateContext(query));
    }

    private String withDateContext(String instruction) {
        LocalDate today = LocalDate.now();
        return "当前日期：" + today
                + "；今天：" + today
                + "；明天：" + today.plusDays(1)
                + "；后天：" + today.plusDays(2)
                + "。\n用户指令：" + instruction;
    }
}
