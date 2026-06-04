package com.qiniu.back.module.chat.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiniu.back.module.chat.mcp.McpToolRegistry;
import com.qiniu.back.module.chat.rag.RagHit;
import com.qiniu.back.module.chat.rag.RagService;
import com.qiniu.back.util.PromptLoader;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Supervisor 的工具箱——管理三个子 Agent 实例，并提供 Supervisor 视角的工具定义和执行。
 */
@Slf4j
@Component
public class SupervisorTools {

    private static final double Planner_Tem = 0.1;
    private static final double Query_Tem = 0.3;
    private static final double Executor_Tem = 0.3;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private McpToolRegistry toolRegistry;

    @Autowired(required = false)
    private RagService ragService;

    private final ObjectMapper mapper = new ObjectMapper();

    private SubAgent plannerAgent;
    private SubAgent queryAgent;
    private SubAgent executorAgent;
    private List<ToolSpecification> supervisorToolSpecs;

    /** Query 子 Agent 拥有的工具（只读） */
    private static final Set<String> QUERY_TOOLS = Set.of(
            "queryTodoList", "queryMonthCount", "queryDayDetail");

    /** Executor 子 Agent 拥有全部工具（读写 + 查询自检） */
    private static final Set<String> EXECUTOR_TOOLS = Set.of(
            "createTodo", "deleteTodo", "updateTodo",
            "toggleTodoDate", "saveDailyNote",
            "removeTodoDay", "addTodoDay",
            "queryTodoList", "queryDayDetail", "queryMonthCount");

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
                .chatModel(chatModel)
                .systemPrompt(PromptLoader.load("planner-system.txt"))
                .toolExecutor((name, args) -> "[Planner] 不应该被调用工具: " + name)
                .temperature(Planner_Tem)
                .maxRounds(1)
                .maxRetries(1)
                .correctionHint("\n\n[纠正提示] 你上次的输出不是有效JSON。请只输出JSON对象，以{开头以}结尾，不要加任何markdown代码块标记或额外文字。")
                .build();

        this.queryAgent = SubAgent.builder()
                .name("Query")
                .chatModel(chatModel)
                .systemPrompt(PromptLoader.load("query-system.txt"))
                .tools(readTools)
                .toolExecutor(toolRegistry::execute)
                .temperature(Query_Tem)
                .maxRounds(3)
                .maxRetries(1)
                .correctionHint("\n\n[纠正提示] 上次查询返回无数据或结果为空。请使用更通用的查询条件，如先调queryTodoList列出所有待办，再精确定位。")
                .build();

        this.executorAgent = SubAgent.builder()
                .name("Executor")
                .chatModel(chatModel)
                .systemPrompt(PromptLoader.load("executor-system.txt"))
                .tools(writeTools)
                .toolExecutor(toolRegistry::execute)
                .temperature(Executor_Tem)
                .maxRounds(3)
                .maxRetries(1)
                .correctionHint("\n\n[纠正提示] 部分工具返回了错误（{\"error\":...）。请先调queryTodoList确认当前数据状态，再重试失败的操作，必要时尝试替代方案。")
                .build();


        this.supervisorToolSpecs = buildSupervisorSpecs();
    }

    /** Supervisor 可调度的子 Agent 工具定义 */
    public List<ToolSpecification> getSupervisorSpecs() {
        return supervisorToolSpecs;
    }

    /**
     * Supervisor 工具执行入口——按工具名路由到对应子 Agent。
     *
     * @param toolName      工具名：plan_task / query_calendar / execute_task
     * @param argumentsJson 工具参数 JSON
     * @return 子 Agent 执行结果
     */
    public String executeSupervisorTool(String toolName, String argumentsJson) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = mapper.readValue(argumentsJson, Map.class);

            return switch (toolName) {
                case "plan_task" -> {
                    String requirement = (String) args.get("requirement");
                    yield plannerAgent.execute(buildPlannerRagContext(requirement) + requirement);
                }
                case "query_calendar" -> queryAgent.execute((String) args.get("query"));
                case "execute_task" -> executorAgent.execute((String) args.get("instruction"));
                default -> "未知的 Supervisor 工具: " + toolName;
            };
        } catch (Exception e) {
            return "Supervisor 工具调用失败 (" + toolName + "): " + e.getMessage();
        }
    }

    // ==================== Planner RAG 上下文 ====================

    private String buildPlannerRagContext(String requirement) {
        if (ragService == null) return "";

        try {
            List<RagHit> hits = ragService.search(requirement);
            if (hits.isEmpty()) return "";

            StringBuilder sb = new StringBuilder("## 参考语料\n");
            sb.append("以下是从知识库检索到的相关规划参考，请优先参考这些内容制定计划：\n");
            for (int i = 0; i < hits.size(); i++) {
                RagHit h = hits.get(i);
                sb.append(String.format("- (%s) %s\n", h.getSection(), h.getText()));
            }
            sb.append("\n## 用户需求\n");
            return sb.toString();
        } catch (Exception e) {
            log.warn("[Planner] RAG 检索失败: {}", e.getMessage());
            return "";
        }
    }

    // ==================== Supervisor 工具定义构建 ====================

    private List<ToolSpecification> buildSupervisorSpecs() {
        List<ToolSpecification> specs = new ArrayList<>();

        specs.add(ToolSpecification.builder()
                .name("plan_task")
                .description("""
                        将复杂的日程需求交给规划师Agent制定计划。
                        适用场景：用户说"帮我安排"、"制定学习计划"、"规划旅行"等需要拆分为多个待办的复杂需求。
                        规划师会分析需求并输出JSON格式的详细待办计划，包含标题、日期范围、每周执行日和颜色。
                        """)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("requirement", "用户的完整需求描述，包含目标、时间范围、约束条件")
                        .required("requirement")
                        .build())
                .build());

        specs.add(ToolSpecification.builder()
                .name("query_calendar")
                .description("""
                        查询用户已有的日程信息。在执行任何创建/修改/删除操作前必须先调用此工具查重。
                        可以查询待办列表、某月待办分布、某天详细日程和日记。
                        """)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("query", "查询指令，如：'查询7月所有待办数'、'列出所有待办'、'查询明天的日程'")
                        .required("query")
                        .build())
                .build());

        specs.add(ToolSpecification.builder()
                .name("execute_task")
                .description("""
                        将具体的待办创建/修改/删除指令交给执行者Agent。
                        指令中应包含完整的待办参数（标题、日期、颜色、周几执行等）。
                        适用于批量创建多个待办，或修改/删除已有待办。
                        """)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("instruction", "具体的操作指令，包含所有必要参数和待办详情")
                        .required("instruction")
                        .build())
                .build());

        return specs;
    }
}
