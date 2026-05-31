package com.qiniu.back.module.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiniu.back.domain.todo.dto.TodoCreateDTO;
import com.qiniu.back.domain.todo.dto.TodoUpdateDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.*;

/**
 * 原生 OpenAI Chat Completions API 客户端，支持 Tool Calling
 */
@Slf4j
@Service
public class OpenAiService {

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;
    private final ChatToolService toolService;

    @Value("${app.ai.base-url}")
    private String baseUrl;

    @Value("${app.ai.api-key}")
    private String apiKey;

    @Value("${app.ai.model}")
    private String model;

    public OpenAiService(ChatToolService toolService) {
        this.restTemplate = new RestTemplate();
        this.mapper = new ObjectMapper();
        this.toolService = toolService;
    }

    private static final int MAX_TOOL_ROUNDS = 5;

    /**
     * 发送对话请求，自动处理多轮 Tool Calling 循环
     */
    public String chat(String systemPrompt, List<Map<String, String>> messages) {
        List<Map<String, Object>> openAiMessages = buildMessages(systemPrompt, messages);

        // Tool calling loop: 持续调用直到模型返回纯文本
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            Map<String, Object> response = callOpenAi(openAiMessages, true);
            Map<String, Object> choice = getFirstChoice(response);
            Map<String, Object> msg = (Map<String, Object>) choice.get("message");

            if (!msg.containsKey("tool_calls")) {
                return (String) msg.getOrDefault("content", "");
            }

            // 执行 tool calls 并回传结果
            openAiMessages.add(msg);
            List<Map<String, Object>> toolResults = executeToolCalls(msg);
            for (Map<String, Object> tr : toolResults) {
                openAiMessages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", tr.get("id"),
                        "content", tr.get("result")
                ));
            }
        }

        // 超出最大轮次后，强制让模型以文本回复
        Map<String, Object> response = callOpenAi(openAiMessages, false);
        Map<String, Object> choice = getFirstChoice(response);
        Map<String, Object> finalMsg = (Map<String, Object>) choice.get("message");
        return (String) finalMsg.getOrDefault("content", "");
    }

    // ==================== 内部实现 ====================

    private List<Map<String, Object>> buildMessages(String systemPrompt, List<Map<String, String>> history) {
        List<Map<String, Object>> messages = new ArrayList<>();

        messages.add(Map.of("role", "system", "content", systemPrompt));

        for (Map<String, String> m : history) {
            String role = m.get("role");
            String content = m.get("content");
            if (content != null && !content.isEmpty()) {
                messages.add(Map.of("role", role, "content", content));
            }
        }
        return messages;
    }

    private Map<String, Object> callOpenAi(List<Map<String, Object>> messages, boolean withTools) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("temperature", 0.7);
            if (withTools) {
                body.put("tools", getToolDefinitions());
                body.put("tool_choice", "auto");
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            String url = baseUrl + "/v1/chat/completions";
            String requestJson = mapper.writeValueAsString(body);
            log.info("OpenAI 请求: model={}, messages={}条", model, messages.size());

            String responseJson = restTemplate.postForObject(url, new HttpEntity<>(requestJson, headers), String.class);

            return mapper.readValue(responseJson, Map.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 序列化失败", e);
        }
    }

    private Map<String, Object> getFirstChoice(Map<String, Object> response) {
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("AI 返回空响应");
        }
        return choices.get(0);
    }

    /**
     * 执行 tool calls 并将结果回传
     */
    private List<Map<String, Object>> executeToolCalls(Map<String, Object> assistantMsg) {
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) assistantMsg.get("tool_calls");
        List<Map<String, Object>> results = new ArrayList<>();

        for (Map<String, Object> tc : toolCalls) {
            String id = (String) tc.get("id");
            Map<String, Object> function = (Map<String, Object>) tc.get("function");
            String funcName = (String) function.get("name");
            String arguments = (String) function.get("arguments");

            log.info("Tool call: {} args: {}", funcName, arguments);

            String result;
            try {
                result = executeTool(funcName, arguments);
            } catch (Exception e) {
                log.error("Tool 执行失败: {}", funcName, e);
                result = "执行失败: " + e.getMessage();
            }

            results.add(Map.of("id", id, "result", result));
        }
        return results;
    }

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

    private String executeTool(String name, String arguments) throws JsonProcessingException {
        Map<String, Object> args = mapper.readValue(arguments, Map.class);

        switch (name) {
            case "createTodo": {
                String title = (String) args.get("title");
                String color = args.get("color") instanceof String s ? s : "#5c4b37";
                String dayContent = args.get("dayContent") instanceof String s ? s : null;
                String startDate = (String) args.get("startDate");
                String endDate = (String) args.get("endDate");
                List<Integer> weekDays = toWeekDayList(args.get("weekDays"));

                TodoCreateDTO dto = new TodoCreateDTO();
                dto.setTitle(title);
                dto.setColor(color);
                dto.setDayContent(dayContent != null ? dayContent : title);
                dto.setStartDate(LocalDate.parse(startDate));
                dto.setEndDate(LocalDate.parse(endDate));
                dto.setWeekDays(weekDays);

                return toolService.createTodo(dto);
            }
            case "queryMonthCount": {
                int year = toInt(args.get("year"));
                int month = toInt(args.get("month"));
                return toolService.queryMonthCount(year, month);
            }
            case "queryDayDetail": {
                String date = (String) args.get("date");
                return toolService.queryDayDetail(date);
            }
            case "queryTodoList": {
                return toolService.queryTodoList();
            }
            case "deleteTodo": {
                Long todoId = toLong(args.get("todoId"));
                return toolService.deleteTodo(todoId);
            }
            case "updateTodo": {
                Long todoId = toLong(args.get("todoId"));
                String title = (String) args.get("title");
                String color = args.get("color") instanceof String s ? s : "#5c4b37";
                String dayContent = args.get("dayContent") instanceof String s ? s : null;
                String startDate = (String) args.get("startDate");
                String endDate = (String) args.get("endDate");
                List<Integer> weekDays = toWeekDayList(args.get("weekDays"));

                TodoUpdateDTO dto = new TodoUpdateDTO();
                dto.setTitle(title);
                dto.setColor(color);
                dto.setDayContent(dayContent != null ? dayContent : title);
                dto.setStartDate(LocalDate.parse(startDate));
                dto.setEndDate(LocalDate.parse(endDate));
                dto.setWeekDays(weekDays);

                return toolService.updateTodo(todoId, dto);
            }
            case "toggleTodoDate": {
                Long todoId = toLong(args.get("todoId"));
                String date = (String) args.get("date");
                return toolService.toggleTodoDate(todoId, date);
            }
            case "saveDailyNote": {
                String date = (String) args.get("date");
                String content = (String) args.get("content");
                return toolService.saveDailyNote(date, content);
            }
            default:
                return "未知的工具: " + name;
        }
    }

    /**
     * OpenAI Tool Definitions
     */
    private List<Map<String, Object>> getToolDefinitions() {
        return List.of(
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "createTodo",
                                "description", "创建一个新的待办目标。仅在用户明确要求创建且已确认后才调用。用户说删除/去掉/移除时绝对不要调这个工具！",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "title", Map.of("type", "string", "description", "目标名称"),
                                                "color", Map.of("type", "string", "description", "十六进制颜色，如#4CAF50"),
                                                "dayContent", Map.of("type", "string", "description", "每日具体任务描述"),
                                                "startDate", Map.of("type", "string", "description", "开始日期 yyyy-MM-dd"),
                                                "endDate", Map.of("type", "string", "description", "结束日期 yyyy-MM-dd"),
                                                "weekDays", Map.of("type", "array",
                                                        "items", Map.of("type", "integer"),
                                                        "description", "每周执行日 1=周一至7=周日")
                                        ),
                                        "required", List.of("title", "startDate", "endDate", "weekDays")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "queryMonthCount",
                                "description", "查询某个月每天的待办数量",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "year", Map.of("type", "integer", "description", "年份"),
                                                "month", Map.of("type", "integer", "description", "月份")
                                        ),
                                        "required", List.of("year", "month")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "queryDayDetail",
                                "description", "查询某天的所有待办和日记内容",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "date", Map.of("type", "string", "description", "日期 yyyy-MM-dd")
                                        ),
                                        "required", List.of("date")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "queryTodoList",
                                "description", "查询当前用户的所有待办目标列表，返回每个目标的ID、名称、日期范围、状态。这是获取待办真实状态的唯一方式，删除或修改待办前必须先调用此工具获取最新ID，操作后必须再次调用以验证结果。不要用对话历史推断待办是否存在！",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of()
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "deleteTodo",
                                "description", "删除一个待办目标。可能因ID不存在等原因失败，调用后必须用 queryTodoList 验证是否真的删除了。用户说删除/去掉/移除/取消XX时调用此工具。必须先调用 queryTodoList 获取待办ID，用户确认后才能调用！不要在用户说删除时去调用 createTodo！",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "todoId", Map.of("type", "integer", "description", "待办目标ID")
                                        ),
                                        "required", List.of("todoId")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "updateTodo",
                                "description", "修改待办。跨天改期（如改成后天）时修改 startDate/endDate；同日改时分（如改到下午）时修改 dayContent。先从 queryTodoList 获取原值，未修改的字段填原值。修改操作无需用户确认，直接调用。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "todoId", Map.of("type", "integer", "description", "待办目标ID"),
                                                "title", Map.of("type", "string", "description", "目标名称"),
                                                "color", Map.of("type", "string", "description", "十六进制颜色，如#4CAF50"),
                                                "dayContent", Map.of("type", "string", "description", "每日具体任务描述"),
                                                "startDate", Map.of("type", "string", "description", "开始日期 yyyy-MM-dd"),
                                                "endDate", Map.of("type", "string", "description", "结束日期 yyyy-MM-dd"),
                                                "weekDays", Map.of("type", "array",
                                                        "items", Map.of("type", "integer"),
                                                        "description", "每周执行日 1=周一至7=周日")
                                        ),
                                        "required", List.of("todoId", "title", "startDate", "endDate", "weekDays")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "toggleTodoDate",
                                "description", "切换某天某个待办的完成状态（完成↔未完成）。用户说'完成'/'搞定'/'做完了'时调用。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "todoId", Map.of("type", "integer", "description", "待办目标ID"),
                                                "date", Map.of("type", "string", "description", "日期 yyyy-MM-dd")
                                        ),
                                        "required", List.of("todoId", "date")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "saveDailyNote",
                                "description", "保存某天的日记内容。用户说'写日记'/'记录一下'/'记下来'时调用。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "date", Map.of("type", "string", "description", "日期 yyyy-MM-dd"),
                                                "content", Map.of("type", "string", "description", "日记内容")
                                        ),
                                        "required", List.of("date", "content")
                                )
                        )
                )
        );
    }
}
