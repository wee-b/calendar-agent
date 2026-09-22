package com.qiniu.back.module.assistant.agent;

import com.qiniu.back.module.assistant.config.AgentModelBeans;
import com.qiniu.back.module.assistant.tool.McpToolRegistry;
import com.qiniu.back.util.PromptLoader;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Handles idle chat and read-only calendar queries. */
@Component
public class ChatAgent {

    private static final Set<String> QUERY_TOOLS = Set.of(
            "queryTodoList", "queryMonthCount", "queryDayDetail");
    private final ChatModel chatModel;
    private final McpToolRegistry toolRegistry;
    private AgentRunner dialogueAgent;
    private AgentRunner queryAgent;

    public ChatAgent(@Qualifier(AgentModelBeans.CHAT) ChatModel chatModel, McpToolRegistry toolRegistry) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    void init() {
        dialogueAgent = AgentRunner.builder()
                .name("ChatDialogue")
                .chatModel(chatModel)
                .systemPrompt(PromptLoader.load("chat-system.txt"))
                .tools(List.of())
                .toolExecutor((name, arguments) -> "")
                .temperature(0.5)
                .maxRounds(1)
                .maxRetries(0)
                .correctionHint("")
                .build();
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

    public String chat(String message) {
        return dialogueAgent.execute(withDateContext(message));
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
