package com.qiniu.back.module.chat.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiniu.back.domain.todo.dto.TodoCreateDTO;
import com.qiniu.back.domain.todo.dto.TodoUpdateDTO;
import com.qiniu.back.module.chat.service.ChatToolService;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;

/**
 * MCP 工具注册中心，统一管理所有工具的定义与执行逻辑。
 * ChatServiceImpl 和 McpController 共用此注册中心。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolRegistry {

    private final ChatToolService toolService;
    private final ObjectMapper mapper = new ObjectMapper();

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

    /**
     * 执行工具并返回结果字符串
     */
    public String execute(String name, String argumentsJson) {
        McpToolDefinition tool = tools.get(name);
        if (tool == null) {
            return "未知的工具: " + name;
        }
        try {
            Map<String, Object> args = mapper.readValue(argumentsJson, Map.class);
            return tool.getExecutor().apply(args);
        } catch (JsonProcessingException e) {
            log.error("MCP 工具参数解析失败: {}", name, e);
            return "参数解析失败: " + e.getMessage();
        } catch (Exception e) {
            log.error("MCP 工具执行失败: {}", name, e);
            return "执行失败: " + e.getMessage();
        }
    }

    // ================= 工具注册 ==================

    private void registerCreateTodo() {
        tools.put("createTodo", McpToolDefinition.builder()
                .name("createTodo")
                .description("创建一个新的待办目标。仅在用户明确要求创建且已确认后才调用。")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "title", Map.of("type", "string", "description", "目标名称"),
                                "color", Map.of("type", "string", "description", "十六进制颜色，如#4CAF50"),
                                "dayContent", Map.of("type", "string", "description", "每日具体任务描述"),
                                "startDate", Map.of("type", "string", "description", "开始日期 yyy-MM-dd"),
                                "endDate", Map.of("type", "string", "description", "结束日期 yyy-MM-dd"),
                                "weekDays", Map.of("type", "array",
                                        "items", Map.of("type", "integer"),
                                        "description", "每周执行日 1=周一至7=周日")
                        ),
                        "required", List.of("title", "startDate", "endDate", "weekDays")
                ))
                .executor(args -> {
                    TodoCreateDTO dto = new TodoCreateDTO();
                    dto.setTitle((String) args.get("title"));
                    dto.setColor(args.get("color") instanceof String s ? s : "#5c4b37");
                    dto.setDayContent(args.get("dayContent") instanceof String s ? s : (String) args.get("title"));
                    dto.setStartDate(LocalDate.parse((String) args.get("startDate")));
                    dto.setEndDate(LocalDate.parse((String) args.get("endDate")));
                    dto.setWeekDays(toWeekDayList(args.get("weekDays")));
                    return toolService.createTodo(dto);
                })
                .build());
    }

    private void registerQueryMonthCount() {
        tools.put("queryMonthCount", McpToolDefinition.builder()
                .name("queryMonthCount")
                .description("查询某个月每天的待办数量")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "year", Map.of("type", "integer", "description", "年份"),
                                "month", Map.of("type", "integer", "description", "月份")
                        ),
                        "required", List.of("year", "month")
                ))
                .executor(args -> toolService.queryMonthCount(
                        toInt(args.get("year")), toInt(args.get("month"))))
                .build());
    }

    private void registerQueryDayDetail() {
        tools.put("queryDayDetail", McpToolDefinition.builder()
                .name("queryDayDetail")
                .description("查询某天的所有待办和日记内容")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "date", Map.of("type", "string", "description", "日期 yyy-MM-dd")
                        ),
                        "required", List.of("date")
                ))
                .executor(args -> toolService.queryDayDetail((String) args.get("date")))
                .build());
    }

    private void registerQueryTodoList() {
        tools.put("queryTodoList", McpToolDefinition.builder()
                .name("queryTodoList")
                .description("查询当前用户的所有待办目标列表，返回每个目标的ID、名称、日期范围、状态。删除或修改待办前必须先调此工具获取最新ID，操作后必须再次调用验证结果。")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .executor(args -> toolService.queryTodoList())
                .build());
    }

    private void registerDeleteTodo() {
        tools.put("deleteTodo", McpToolDefinition.builder()
                .name("deleteTodo")
                .description("删除一个待办目标。必须先调用 queryTodoList 获取待办ID，用户确认后才能调用。")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "todoId", Map.of("type", "integer", "description", "待办目标ID")
                        ),
                        "required", List.of("todoId")
                ))
                .executor(args -> toolService.deleteTodo(toLong(args.get("todoId"))))
                .build());
    }

    private void registerUpdateTodo() {
        tools.put("updateTodo", McpToolDefinition.builder()
                .name("updateTodo")
                .description("修改待办。跨天改期时修改 startDate/endDate；同日改时分时修改 dayContent。所有字段必填，未修改字段从 queryTodoList 取原值。")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "todoId", Map.of("type", "integer", "description", "待办目标ID"),
                                "title", Map.of("type", "string", "description", "目标名称"),
                                "color", Map.of("type", "string", "description", "十六进制颜色"),
                                "dayContent", Map.of("type", "string", "description", "每日具体任务描述"),
                                "startDate", Map.of("type", "string", "description", "开始日期 yyy-MM-dd"),
                                "endDate", Map.of("type", "string", "description", "结束日期 yyy-MM-dd"),
                                "weekDays", Map.of("type", "array",
                                        "items", Map.of("type", "integer"),
                                        "description", "每周执行日 1=周一至7=周日")
                        ),
                        "required", List.of("todoId", "title", "startDate", "endDate", "weekDays")
                ))
                .executor(args -> {
                    TodoUpdateDTO dto = new TodoUpdateDTO();
                    dto.setTitle((String) args.get("title"));
                    dto.setColor(args.get("color") instanceof String s ? s : "#5c4b37");
                    dto.setDayContent(args.get("dayContent") instanceof String s ? s : (String) args.get("title"));
                    dto.setStartDate(LocalDate.parse((String) args.get("startDate")));
                    dto.setEndDate(LocalDate.parse((String) args.get("endDate")));
                    dto.setWeekDays(toWeekDayList(args.get("weekDays")));
                    return toolService.updateTodo(toLong(args.get("todoId")), dto);
                })
                .build());
    }

    private void registerToggleTodoDate() {
        tools.put("toggleTodoDate", McpToolDefinition.builder()
                .name("toggleTodoDate")
                .description("切换某天某个待办的完成状态（完成↔未完成）")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "todoId", Map.of("type", "integer", "description", "待办目标ID"),
                                "date", Map.of("type", "string", "description", "日期 yyy-MM-dd")
                        ),
                        "required", List.of("todoId", "date")
                ))
                .executor(args -> toolService.toggleTodoDate(
                        toLong(args.get("todoId")), (String) args.get("date")))
                .build());
    }

    private void registerSaveDailyNote() {
        tools.put("saveDailyNote", McpToolDefinition.builder()
                .name("saveDailyNote")
                .description("保存某天的日记内容")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "date", Map.of("type", "string", "description", "日期 yyy-MM-dd"),
                                "content", Map.of("type", "string", "description", "日记内容")
                        ),
                        "required", List.of("date", "content")
                ))
                .executor(args -> toolService.saveDailyNote(
                        (String) args.get("date"), (String) args.get("content")))
                .build());
    }

    private void registerRemoveTodoDay() {
        tools.put("removeTodoDay", McpToolDefinition.builder()
                .name("removeTodoDay")
                .description("从待办目标中移除指定的一天，其他天不受影响。仅在用户明确说\"取消某天\"、\"跳过某天\"、\"删除某天的计划\"时调用。绝对不要对整个待办目标调用此工具，deleteTodo 才是删除整个目标的。")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "todoId", Map.of("type", "integer", "description", "待办目标ID（从 queryTodoList 获取）"),
                                "date", Map.of("type", "string", "description", "要移除的日期 yyy-MM-dd")
                        ),
                        "required", List.of("todoId", "date")
                ))
                .executor(args -> toolService.removeTodoDay(
                        toLong(args.get("todoId")), (String) args.get("date")))
                .build());
    }

    private void registerAddTodoDay() {
        tools.put("addTodoDay", McpToolDefinition.builder()
                .name("addTodoDay")
                .description("给已有待办目标增加一天。用于补打卡、临时加一天、把某天调换到另一个日期等场景。先调用 removeTodoDay 移除旧日期，再调用 addTodoDay 添加新日期即可实现单天调换。")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "todoId", Map.of("type", "integer", "description", "待办目标ID（从 queryTodoList 获取）"),
                                "date", Map.of("type", "string", "description", "要新增的日期 yyy-MM-dd"),
                                "dayContent", Map.of("type", "string", "description", "当天的具体任务描述，可选")
                        ),
                        "required", List.of("todoId", "date")
                ))
                .executor(args -> toolService.addTodoDay(
                        toLong(args.get("todoId")),
                        (String) args.get("date"),
                        args.get("dayContent") instanceof String s ? s : null))
                .build());
    }

    // ================= 类型转换工具 ==================

    private Long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) return Long.parseLong(s);
        throw new IllegalArgumentException("无法转换为数字: " + value);
    }

    private int toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) return Integer.parseInt(s);
        throw new IllegalArgumentException("无法转换为数字: " + value);
    }

    private List<Integer> toWeekDayList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(item -> {
                if (item instanceof Number n) return n.intValue();
                if (item instanceof String s) return Integer.parseInt(s);
                throw new IllegalArgumentException("无法解析星期: " + item);
            }).toList();
        }
        throw new IllegalArgumentException("weekDays 不是数组");
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
                    "inputSchema", tool.getInputSchema()
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
                    .parameters(buildJsonSchema(tool.getName(), tool.getInputSchema()))
                    .build());
        }
        return result;
    }

    private JsonObjectSchema buildJsonSchema(String toolName, Map<String, Object> schema) {
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