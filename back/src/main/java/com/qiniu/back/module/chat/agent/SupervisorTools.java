package com.qiniu.back.module.chat.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiniu.back.module.chat.mcp.McpToolRegistry;
import com.qiniu.back.util.PromptLoader;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Supervisor 的工具箱——管理三个子 Agent 实例，并提供 Supervisor 视角的工具定义和执行。
 */
@Component
public class SupervisorTools {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private McpToolRegistry toolRegistry;

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

        this.plannerAgent = new SubAgent(
                "Planner",
                chatModel,
                PromptLoader.load("planner-system.txt"),
                List.of(), // 无工具，纯推理
                (name, args) -> "[Planner] 不应该被调用工具: " + name
        );

        this.queryAgent = new SubAgent(
                "Query",
                chatModel,
                PromptLoader.load("query-system.txt"),
                readTools,
                toolRegistry::execute
        );

        this.executorAgent = new SubAgent(
                "Executor",
                chatModel,
                PromptLoader.load("executor-system.txt"),
                writeTools,
                toolRegistry::execute
        );

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
                case "plan_task" -> plannerAgent.execute((String) args.get("requirement"));
                case "query_calendar" -> queryAgent.execute((String) args.get("query"));
                case "execute_task" -> executorAgent.execute((String) args.get("instruction"));
                default -> "未知的 Supervisor 工具: " + toolName;
            };
        } catch (Exception e) {
            return "Supervisor 工具调用失败 (" + toolName + "): " + e.getMessage();
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
