package com.qiniu.back.module.assistant.agent;

import com.qiniu.back.module.assistant.config.AgentModelBeans;
import com.qiniu.back.module.assistant.domain.model.PlanDraft;
import com.qiniu.back.module.assistant.service.PlanDraftService;
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

/** Executes confirmed write operations and applies confirmed plan drafts. */
@Component
public class ExecutorAgent {

    private static final Set<String> EXECUTOR_TOOLS = Set.of(
            "createTodo", "deleteTodo", "updateTodo", "toggleTodoDate",
            "saveDailyNote", "removeTodoDay", "addTodoDay",
            "queryTodoList", "queryDayDetail", "queryMonthCount");

    private final ChatModel chatModel;
    private final McpToolRegistry toolRegistry;
    private final PlanDraftService planDraftService;
    private AgentRunner runner;

    public ExecutorAgent(@Qualifier(AgentModelBeans.EXECUTOR) ChatModel chatModel,
                         McpToolRegistry toolRegistry,
                         PlanDraftService planDraftService) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.planDraftService = planDraftService;
    }

    @PostConstruct
    void init() {
        List<ToolSpecification> tools = toolRegistry.toLangChain4jSpecifications().stream()
                .filter(tool -> EXECUTOR_TOOLS.contains(tool.name()))
                .toList();
        runner = AgentRunner.builder()
                .name("Executor")
                .chatModel(chatModel)
                .systemPrompt(PromptLoader.load("executor-system.txt"))
                .tools(tools)
                .toolExecutor(toolRegistry::execute)
                .temperature(0.1)
                .maxRounds(3)
                .maxRetries(0)
                .correctionHint("")
                .build();
    }

    public String execute(String instruction) {
        LocalDate today = LocalDate.now();
        String context = "当前日期：" + today
                + "；今天：" + today
                + "；明天：" + today.plusDays(1)
                + "；后天：" + today.plusDays(2)
                + "。\n用户指令：" + instruction;
        return runner.execute(context);
    }

    public String applyPlan(PlanDraft draft) {
        return planDraftService.buildSyncReply(planDraftService.syncDraft(draft));
    }
}
