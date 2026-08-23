package com.qiniu.back.module.chat.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiniu.back.module.chat.mcp.McpToolRegistry;
import com.qiniu.back.module.chat.rag.RagHit;
import com.qiniu.back.module.chat.rag.RagService;
import com.qiniu.back.module.chat.service.PlanDraftService;
import com.qiniu.back.util.ChatSessionContext;
import com.qiniu.back.util.LoginUserContext;
import com.qiniu.back.util.PromptLoader;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class SupervisorTools {

    private static final double PLANNER_TEMPERATURE = 0.1;
    private static final double QUERY_TEMPERATURE = 0.3;
    private static final double EXECUTOR_TEMPERATURE = 0.3;

    private static final Set<String> QUERY_TOOLS = Set.of(
            "queryTodoList", "queryMonthCount", "queryDayDetail");

    private static final Set<String> EXECUTOR_TOOLS = Set.of(
            "createTodo", "deleteTodo", "updateTodo",
            "toggleTodoDate", "saveDailyNote",
            "removeTodoDay", "addTodoDay",
            "queryTodoList", "queryDayDetail", "queryMonthCount");

    @Autowired
    private ChatModel chatModel;

    @Autowired
    @Qualifier("plannerChatModel")
    private ChatModel plannerChatModel;

    @Autowired
    private McpToolRegistry toolRegistry;

    @Autowired(required = false)
    private RagService ragService;

    @Autowired
    private PlanDraftService planDraftService;

    private final ObjectMapper mapper = new ObjectMapper();

    private SubAgent plannerAgent;
    private SubAgent queryAgent;
    private SubAgent executorAgent;
    private List<ToolSpecification> supervisorToolSpecs;

    @PostConstruct
    public void init() {
        List<ToolSpecification> allSpecs = toolRegistry.toLangChain4jSpecifications();

        List<ToolSpecification> readTools = allSpecs.stream()
                .filter(t -> QUERY_TOOLS.contains(t.name()))
                .toList();

        List<ToolSpecification> writeTools = allSpecs.stream()
                .filter(t -> EXECUTOR_TOOLS.contains(t.name()))
                .toList();

        this.plannerAgent = SubAgent.builder()
                .name("Planner")
                .chatModel(plannerChatModel)
                .systemPrompt(PromptLoader.load("planner-system.txt"))
                .toolExecutor((name, args) -> "[Planner] tool calls are not allowed: " + name)
                .temperature(PLANNER_TEMPERATURE)
                .maxRounds(1)
                .maxRetries(1)
                .correctionHint("\n\nReturn valid raw JSON only. Do not use markdown fences or extra text.")
                .build();

        this.queryAgent = SubAgent.builder()
                .name("Query")
                .chatModel(chatModel)
                .systemPrompt(PromptLoader.load("query-system.txt"))
                .tools(readTools)
                .toolExecutor(toolRegistry::execute)
                .temperature(QUERY_TEMPERATURE)
                .maxRounds(3)
                .maxRetries(1)
                .correctionHint("\n\nIf the query returned no data, try a broader read-only query such as queryTodoList.")
                .build();

        this.executorAgent = SubAgent.builder()
                .name("Executor")
                .chatModel(chatModel)
                .systemPrompt(PromptLoader.load("executor-system.txt"))
                .tools(writeTools)
                .toolExecutor(toolRegistry::execute)
                .temperature(EXECUTOR_TEMPERATURE)
                .maxRounds(3)
                .maxRetries(1)
                .correctionHint("\n\nIf a tool returned an error JSON, query current todo data and retry only if safe.")
                .build();

        this.supervisorToolSpecs = buildSupervisorSpecs();
    }

    public List<ToolSpecification> getSupervisorSpecs() {
        return supervisorToolSpecs;
    }

    public String planTask(String requirement) {
        String planJson = plannerAgent.execute(buildPlannerRagContext(requirement) + requirement);
        Long draftId = planDraftService.savePendingDraft(
                LoginUserContext.getUserId(),
                ChatSessionContext.getSessionId(),
                requirement,
                planJson);
        return planDraftService.buildPreviewReply(draftId, planJson);
    }

    public String queryCalendar(String query) {
        return queryAgent.execute(query);
    }

    public String executeTask(String instruction) {
        return executorAgent.execute(instruction);
    }

    public String executeSupervisorTool(String toolName, String argumentsJson) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = mapper.readValue(argumentsJson, Map.class);

            return switch (toolName) {
                case "plan_task" -> planTask((String) args.get("requirement"));
                case "query_calendar" -> queryCalendar((String) args.get("query"));
                case "execute_task" -> executeTask((String) args.get("instruction"));
                default -> "Unknown supervisor tool: " + toolName;
            };
        } catch (Exception e) {
            return "Supervisor tool failed (" + toolName + "): " + e.getMessage();
        }
    }

    private String buildPlannerRagContext(String requirement) {
        if (ragService == null) return "";

        try {
            List<RagHit> hits = ragService.search(requirement);
            if (hits.isEmpty()) return "";

            StringBuilder sb = new StringBuilder("## Reference snippets\n");
            for (RagHit hit : hits) {
                sb.append("- (")
                        .append(hit.getSection())
                        .append(") ")
                        .append(hit.getText())
                        .append("\n");
            }
            sb.append("\n## User requirement\n");
            return sb.toString();
        } catch (Exception e) {
            log.warn("[Planner] RAG search failed: {}", e.getMessage());
            return "";
        }
    }

    private List<ToolSpecification> buildSupervisorSpecs() {
        List<ToolSpecification> specs = new ArrayList<>();

        specs.add(ToolSpecification.builder()
                .name("plan_task")
                .description("""
                        Send a complex scheduling or preparation request to the Planner agent.
                        The Planner returns structured JSON internally; after this tool runs, the JSON is saved as a pending plan draft.
                        This tool returns only a Markdown preview for the user, not the raw JSON.
                        Use this for multi-step plans that need user confirmation before calendar sync.
                        """)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("requirement", "Full user requirement with goal, date range, constraints, and assumptions.")
                        .required("requirement")
                        .build())
                .build());

        specs.add(ToolSpecification.builder()
                .name("query_calendar")
                .description("""
                        Query existing calendar or todo data. This is read-only.
                        Use it to inspect todo lists, day details, or month counts.
                        """)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("query", "Read-only query instruction.")
                        .required("query")
                        .build())
                .build());

        specs.add(ToolSpecification.builder()
                .name("execute_task")
                .description("""
                        Send concrete create, update, delete, toggle, or note-saving instructions to the Executor agent.
                        Do not use this for syncing a confirmed plan draft; confirmed drafts are synced directly by PlanDraftService.
                        """)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("instruction", "Concrete execution instruction with required parameters.")
                        .required("instruction")
                        .build())
                .build());

        return specs;
    }
}
