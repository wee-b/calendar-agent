package com.qiniu.back.module.assistant.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.qiniu.back.domain.dailyNote.dto.DailyNoteSaveDTO;
import com.qiniu.back.domain.dailyNote.dto.DayDetailQueryDTO;
import com.qiniu.back.domain.dailyNote.dto.MonthCountQueryDTO;
import com.qiniu.back.domain.todo.dto.TodoCreateDTO;
import com.qiniu.back.domain.todo.dto.TodoDateToggleDTO;
import com.qiniu.back.domain.todo.dto.TodoUpdateDTO;
import com.qiniu.back.exception.BusinessException;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP 工具注册中心，统一管理所有工具的定义与执行逻辑。
 * Assistant Agent 和 McpController 共用此注册中心。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolRegistry {

    @Getter
    @AllArgsConstructor
    public enum McpToolName {

        CreateTodo(
                "createTodo",
                "创建一个新的待办目标。仅在用户明确要求创建且已确认后才调用。"),
        QueryMonthCount(
                "queryMonthCount",
                "查询某个月每天的待办数量"),
        QueryDayDetail(
                "queryDayDetail",
                "查询某天的所有待办和日记内容"),
        QueryTodoList(
                "queryTodoList",
                "查询当前用户的所有待办目标列表，返回每个目标的ID、名称、日期范围、状态。删除或修改待办前必须先调此工具获取最新ID，操作后必须再次调用验证结果。"),
        DeleteTodo(
                "deleteTodo",
                "删除一个待办目标。必须先调用 queryTodoList 获取待办ID，用户确认后才能调用。"),
        UpdateTodo(
                "updateTodo",
                "修改待办。跨天改期时修改 startDate/endDate；同日改时分时修改 dayContent。所有字段必填，未修改字段从 queryTodoList 取原值。"),
        ToggleTodoDate(
                "toggleTodoDate",
                "切换某天某个待办的完成状态（完成↔未完成）"),
        SaveDailyNote(
                "saveDailyNote",
                "保存某天的日记内容"),
        RemoveTodoDay(
                "removeTodoDay",
                "从待办目标中移除指定的一天，其他天不受影响。仅在用户明确说\"取消某天\"、\"跳过某天\"、\"删除某天的计划\"时调用。绝对不要对整个待办目标调用此工具，deleteTodo 才是删除整个目标的。"),
        AddTodoDay(
                "addTodoDay",
                "给已有待办目标增加一天。用于补打卡、临时加一天、把某天调换到另一个日期等场景。先调用 removeTodoDay 移除旧日期，再调用 addTodoDay 添加新日期即可实现单天调换。");

        private final String name;
        private final String description;
    }

    public record McpToolResult(
            boolean success,
            Object data,
            McpToolError error
    ) {
        public static McpToolResult success(Object data) {
            return new McpToolResult(true, data, null);
        }

        public static McpToolResult failure(
                String code,
                String message,
                boolean retryable
        ) {
            return new McpToolResult(
                    false,
                    null,
                    new McpToolError(code, message, retryable)
            );
        }
    }

    public record McpToolError(
            String code,
            String message,
            boolean retryable
    ) {}

    private final ChatToolService toolService;
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    private final Map<String, McpToolDefinition> tools = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        registerCreateTodo();
        registerQueryMonthCount();
        registerQueryDayDetail();
        registerQueryTodoList();
        registerDeleteTodo();
        registerUpdateTodo();
        registerToggleTodoDate();
        registerSaveDailyNote();
        registerRemoveTodoDay();
        registerAddTodoDay();
    }

    /**
     * 获取所有工具定义
     */
    public List<McpToolDefinition> listTools() {
        return new ArrayList<>(tools.values());
    }

    /**
     * 按名称获取单个工具
     */
    public McpToolDefinition getTool(String name) {
        return tools.get(name);
    }

    /** 保留给现有 Java Agent 的文本接口。 */
    public String execute(String name, String argumentsJson) {
        McpToolResult result = executeResult(name, argumentsJson);
        if (result.success()) {
            try {
                return mapper.writeValueAsString(result.data());
            } catch (JsonProcessingException e) {
                return "{\"error\":\"工具结果序列化失败\"}";
            }
        }
        try {
            return mapper.writeValueAsString(Map.of("error", result.error().message(),
                    "code", result.error().code()));
        } catch (JsonProcessingException e) {
            return "{\"error\":\"工具调用失败\"}";
        }
    }

    /** 供 MCP 网关使用的结构化执行结果。 */
    public McpToolResult executeResult(String name, String argumentsJson) {
        McpToolDefinition tool = tools.get(name);
        if (tool == null) {
            return McpToolResult.failure("UNKNOWN_TOOL", "未知的工具: " + name, false);
        }
        Map<String, Object> args;
        try {
            args = mapper.readValue(argumentsJson, Map.class);
            if (args == null) {
                return McpToolResult.failure("INVALID_ARGUMENTS", "工具参数必须是 JSON 对象", false);
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return McpToolResult.failure("INVALID_ARGUMENTS", "工具参数解析失败: " + e.getMessage(), false);
        }
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) tool.getInputSchema().getOrDefault("required", List.of());
        for (String field : required) {
            if (args.get(field) == null || args.get(field) instanceof String value && value.isBlank()) {
                return McpToolResult.failure("INVALID_ARGUMENTS", "缺少必填参数: " + field, false);
            }
        }
        return executeWithRetry(tool, args);
    }

    private McpToolResult executeWithRetry(McpToolDefinition tool, Map<String, Object> args) {
        int maxAttempts = tool.isReadOnly() && tool.isIdempotent()
                ? tool.getRetryCount() + 1 : 1;
        long delayMs = tool.getRetryDelayMs();

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                return McpToolResult.success(tool.getExecutor().apply(args));
            } catch (Exception e) {
                if (e instanceof BusinessException business) {
                    return McpToolResult.failure("BUSINESS_" + business.getCode(), business.getMsg(), false);
                }
                boolean retryable = isRetryable(e);
                if (attempt < maxAttempts - 1 && retryable) {
                    log.warn("MCP 工具 {} 执行异常(可重试) 第{}/{}次, {}",
                            tool.getName(), attempt + 1, maxAttempts, e.getMessage());
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return McpToolResult.failure("TOOL_INTERRUPTED", "工具调用已中断", false);
                    }
                } else {
                    log.error("MCP 工具 {} 执行失败: {}", tool.getName(), e);
                    return McpToolResult.failure("TOOL_EXECUTION_FAILED",
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), retryable);
                }
            }
        }
        return McpToolResult.failure("TOOL_EXECUTION_FAILED", "重试耗尽", false);
    }

    /**
     * 判断异常是否可重试（仅瞬态故障：DB超时、连接断开、网络抖动）
     */
    private boolean isRetryable(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            String cls = cause.getClass().getName();
            if (cls.contains("SQLException") || cls.contains("TimeoutException")
                    || cls.contains("ConnectException") || cls.contains("SocketException")
                    || cls.contains("DataAccessException") || cls.contains("QueryTimeoutException")
                    || cls.contains("CommandAcceptanceException"))
                return true;
            cause = cause.getCause();
        }
        return false;
    }

    // ================= 工具注册 ==================

    private void registerCreateTodo() {
        tools.put(McpToolName.CreateTodo.getName(), McpToolDefinition.builder()
                .name(McpToolName.CreateTodo.getName())
                .description(McpToolName.CreateTodo.getDescription())
                .readOnly(false).idempotent(false).parallelSafe(false)
                .confirmationRequired(true).timeoutMs(5000).retryCount(0)
                .inputSchema(DtoInputSchema.from(TodoCreateDTO.class))
                .executor(args -> {
                    TodoCreateDTO dto = mapper.convertValue(args, TodoCreateDTO.class);
                    if (dto.getColor() == null) dto.setColor("#5c4b37");
                    if (dto.getDayContent() == null) dto.setDayContent(dto.getTitle());
                    return toolService.createTodo(dto);
                })
                .build());
    }

    private void registerQueryMonthCount() {
        tools.put(McpToolName.QueryMonthCount.getName(), McpToolDefinition.builder()
                .name(McpToolName.QueryMonthCount.getName())
                .description(McpToolName.QueryMonthCount.getDescription())
                .readOnly(true).idempotent(true).parallelSafe(true)
                .confirmationRequired(false).timeoutMs(3000)
                .inputSchema(DtoInputSchema.from(MonthCountQueryDTO.class))
                .executor(args -> {
                    MonthCountQueryDTO dto = mapper.convertValue(args, MonthCountQueryDTO.class);
                    return toolService.queryMonthCount(dto.getYear(), dto.getMonth());
                })
                .build());
    }

    private void registerQueryDayDetail() {
        tools.put(McpToolName.QueryDayDetail.getName(), McpToolDefinition.builder()
                .name(McpToolName.QueryDayDetail.getName())
                .description(McpToolName.QueryDayDetail.getDescription())
                .readOnly(true).idempotent(true).parallelSafe(true)
                .confirmationRequired(false).timeoutMs(3000)
                .inputSchema(DtoInputSchema.from(DayDetailQueryDTO.class))
                .executor(args -> {
                    DayDetailQueryDTO dto = mapper.convertValue(args, DayDetailQueryDTO.class);
                    return toolService.queryDayDetail(dto.getDate().toString());
                })
                .build());
    }

    private void registerQueryTodoList() {
        tools.put(McpToolName.QueryTodoList.getName(), McpToolDefinition.builder()
                .name(McpToolName.QueryTodoList.getName())
                .description(McpToolName.QueryTodoList.getDescription())
                .readOnly(true).idempotent(true).parallelSafe(true)
                .confirmationRequired(false).timeoutMs(3000)
                .inputSchema(DtoInputSchema.empty())
                .executor(args -> toolService.queryTodoList())
                .build());
    }

    private void registerDeleteTodo() {
        tools.put(McpToolName.DeleteTodo.getName(), McpToolDefinition.builder()
                .name(McpToolName.DeleteTodo.getName())
                .description(McpToolName.DeleteTodo.getDescription())
                .readOnly(false).idempotent(false).parallelSafe(false)
                .confirmationRequired(true).timeoutMs(5000).retryCount(0)
                .inputSchema(DtoInputSchema.select(TodoDateToggleDTO.class, "todoId"))
                .executor(args -> {
                    TodoDateToggleDTO dto = mapper.convertValue(args, TodoDateToggleDTO.class);
                    return toolService.deleteTodo(dto.getTodoId());
                })
                .build());
    }

    private void registerUpdateTodo() {
        tools.put(McpToolName.UpdateTodo.getName(), McpToolDefinition.builder()
                .name(McpToolName.UpdateTodo.getName())
                .description(McpToolName.UpdateTodo.getDescription())
                .readOnly(false).idempotent(false).parallelSafe(false)
                .confirmationRequired(true).timeoutMs(5000).retryCount(0)
                .inputSchema(DtoInputSchema.merge(
                        DtoInputSchema.select(TodoDateToggleDTO.class, "todoId"),
                        DtoInputSchema.from(TodoUpdateDTO.class)))
                .executor(args -> {
                    TodoUpdateDTO dto = mapper.convertValue(args, TodoUpdateDTO.class);
                    if (dto.getColor() == null) dto.setColor("#5c4b37");
                    if (dto.getDayContent() == null) dto.setDayContent(dto.getTitle());
                    TodoDateToggleDTO identity = mapper.convertValue(args, TodoDateToggleDTO.class);
                    return toolService.updateTodo(identity.getTodoId(), dto);
                })
                .build());
    }

    private void registerToggleTodoDate() {
        tools.put(McpToolName.ToggleTodoDate.getName(), McpToolDefinition.builder()
                .name(McpToolName.ToggleTodoDate.getName())
                .description(McpToolName.ToggleTodoDate.getDescription())
                .readOnly(false).idempotent(false).parallelSafe(false)
                .confirmationRequired(true).timeoutMs(5000).retryCount(0)
                .inputSchema(DtoInputSchema.from(TodoDateToggleDTO.class))
                .executor(args -> {
                    TodoDateToggleDTO dto = mapper.convertValue(args, TodoDateToggleDTO.class);
                    return toolService.toggleTodoDate(dto.getTodoId(), dto.getTodoDate().toString());
                })
                .build());
    }

    private void registerSaveDailyNote() {
        tools.put(McpToolName.SaveDailyNote.getName(), McpToolDefinition.builder()
                .name(McpToolName.SaveDailyNote.getName())
                .description(McpToolName.SaveDailyNote.getDescription())
                .readOnly(false).idempotent(false).parallelSafe(false)
                .confirmationRequired(true).timeoutMs(5000).retryCount(0)
                .inputSchema(DtoInputSchema.from(DailyNoteSaveDTO.class))
                .executor(args -> {
                    DailyNoteSaveDTO dto = mapper.convertValue(args, DailyNoteSaveDTO.class);
                    return toolService.saveDailyNote(dto.getNoteDate().toString(), dto.getContent());
                })
                .build());
    }

    private void registerRemoveTodoDay() {
        tools.put(McpToolName.RemoveTodoDay.getName(), McpToolDefinition.builder()
                .name(McpToolName.RemoveTodoDay.getName())
                .description(McpToolName.RemoveTodoDay.getDescription())
                .readOnly(false).idempotent(false).parallelSafe(false)
                .confirmationRequired(true).timeoutMs(5000).retryCount(0)
                .inputSchema(DtoInputSchema.from(TodoDateToggleDTO.class))
                .executor(args -> {
                    TodoDateToggleDTO dto = mapper.convertValue(args, TodoDateToggleDTO.class);
                    return toolService.removeTodoDay(dto.getTodoId(), dto.getTodoDate().toString());
                })
                .build());
    }

    private void registerAddTodoDay() {
        tools.put(McpToolName.AddTodoDay.getName(), McpToolDefinition.builder()
                .name(McpToolName.AddTodoDay.getName())
                .description(McpToolName.AddTodoDay.getDescription())
                .readOnly(false).idempotent(false).parallelSafe(false)
                .confirmationRequired(true).timeoutMs(5000).retryCount(0)
                .inputSchema(DtoInputSchema.merge(
                        DtoInputSchema.from(TodoDateToggleDTO.class),
                        DtoInputSchema.select(TodoCreateDTO.class, "dayContent")))
                .executor(args -> {
                    TodoDateToggleDTO dto = mapper.convertValue(args, TodoDateToggleDTO.class);
                    return toolService.addTodoDay(
                            dto.getTodoId(), dto.getTodoDate().toString(), (String) args.get("dayContent"));
                })
                .build());
    }

    // ================= 格式转换 ==================

    /**
     * 转为 OpenAI Function Calling 格式
     */
    public List<Map<String, Object>> toOpenAiTools() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (McpToolDefinition tool : tools.values()) {
            result.add(Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", tool.getName(),
                            "description", tool.getDescription(),
                            "parameters", tool.getInputSchema()
                    )
            ));
        }
        return result;
    }

    /**
     * 转为 MCP tools/list 响应格式
     */
    public List<Map<String, Object>> toMcpToolList() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (McpToolDefinition tool : tools.values()) {
            result.add(Map.of(
                    "name", tool.getName(),
                    "description", tool.getDescription(),
                    "inputSchema", tool.getInputSchema(),
                    "metadata", Map.of(
                            "readOnly", tool.isReadOnly(),
                            "idempotent", tool.isIdempotent(),
                            "parallelSafe", tool.isParallelSafe(),
                            "confirmationRequired", tool.isConfirmationRequired(),
                            "timeoutMs", tool.getTimeoutMs())
            ));
        }
        return result;
    }

    /**
     * 转为 LangChain4j ToolSpecification 列表（用于 OpenAiChatModel / OpenAiStreamingChatModel）
     */
    public List<ToolSpecification> toLangChain4jSpecifications() {
        List<ToolSpecification> result = new ArrayList<>();
        for (McpToolDefinition tool : tools.values()) {
            result.add(ToolSpecification.builder()
                    .name(tool.getName())
                    .description(tool.getDescription())
                    .parameters(buildJsonSchema(tool.getInputSchema()))
                    .build());
        }
        return result;
    }

    private JsonObjectSchema buildJsonSchema(Map<String, Object> schema) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        if (properties != null) {
            for (Map.Entry<String, Object> prop : properties.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> propDef = (Map<String, Object>) prop.getValue();
                String type = (String) propDef.get("type");
                String desc = (String) propDef.get("description");

                builder.addProperty(prop.getKey(), toJsonSchemaElement(type, desc, propDef));
            }
        }

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        if (required != null && !required.isEmpty()) {
            builder.required(required);
        }

        return builder.build();
    }

    private dev.langchain4j.model.chat.request.json.JsonSchemaElement toJsonSchemaElement(
            String type, String description, Map<String, Object> propDef) {
        return switch (type) {
            case "string" -> dev.langchain4j.model.chat.request.json.JsonStringSchema.builder()
                    .description(description).build();
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            case "array" -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> items = (Map<String, Object>) propDef.get("items");
                String itemType = items != null ? (String) items.get("type") : "string";
                yield JsonArraySchema.builder()
                        .description(description)
                        .items(toJsonSchemaElement(itemType, null, items != null ? items : Map.of()))
                        .build();
            }
            default -> dev.langchain4j.model.chat.request.json.JsonStringSchema.builder()
                    .description(description).build();
        };
    }
}
