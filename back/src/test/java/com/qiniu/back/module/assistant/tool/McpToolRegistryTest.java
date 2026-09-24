package com.qiniu.back.module.assistant.tool;

import com.qiniu.back.domain.todo.dto.TodoCreateDTO;
import com.qiniu.back.domain.ErrorCode;
import com.qiniu.back.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class McpToolRegistryTest {

    @Test
    void registersOriginalGeneralPurposeTodoTools() {
        McpToolRegistry registry = new McpToolRegistry(mock(ChatToolService.class));
        registry.init();

        Set<String> names = registry.listTools().stream()
                .map(McpToolDefinition::getName)
                .collect(Collectors.toSet());

        assertTrue(names.containsAll(Set.of(
                "createTodo", "updateTodo", "deleteTodo",
                "queryDayDetail", "removeTodoDay", "addTodoDay", "toggleTodoDate")));
        assertFalse(names.contains("createSingleDayTodo"));
        assertFalse(names.contains("updateTodoDay"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void derivesCreateTodoSchemaFromDto() {
        McpToolRegistry registry = new McpToolRegistry(mock(ChatToolService.class));
        registry.init();

        Map<String, Object> schema = registry.getTool("createTodo").getInputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertEquals(List.of("title", "startDate", "endDate"), schema.get("required"));
        assertEquals("目标名称", ((Map<String, Object>) properties.get("title")).get("description"));
        assertEquals("date", ((Map<String, Object>) properties.get("startDate")).get("format"));
        assertEquals("integer", ((Map<String, Object>)
                ((Map<String, Object>) properties.get("weekDays")).get("items")).get("type"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void exposesExpectedSchemasForAllTools() {
        McpToolRegistry registry = new McpToolRegistry(mock(ChatToolService.class));
        registry.init();

        assertEquals(Set.of("year", "month"), propertyNames(registry, "queryMonthCount"));
        assertEquals(Set.of("date"), propertyNames(registry, "queryDayDetail"));
        assertTrue(propertyNames(registry, "queryTodoList").isEmpty());
        assertEquals(Set.of("todoId"), propertyNames(registry, "deleteTodo"));
        assertEquals(Set.of("todoId", "title", "color", "dayContent", "startDate", "endDate", "weekDays"),
                propertyNames(registry, "updateTodo"));
        assertEquals(Set.of("todoId", "date"), propertyNames(registry, "toggleTodoDate"));
        assertEquals(Set.of("date", "content"), propertyNames(registry, "saveDailyNote"));
        assertEquals(Set.of("todoId", "date"), propertyNames(registry, "removeTodoDay"));
        assertEquals(Set.of("todoId", "date", "dayContent"), propertyNames(registry, "addTodoDay"));
    }

    @Test
    void convertsCreateTodoArgumentsToDto() {
        ChatToolService toolService = mock(ChatToolService.class);
        when(toolService.createTodo(any(TodoCreateDTO.class)))
                .thenReturn(new ChatToolService.TodoMutationResult("created", 1L, "学英语", 1));
        McpToolRegistry registry = new McpToolRegistry(toolService);
        registry.init();

        assertEquals("{\"operation\":\"created\",\"todoId\":1,\"title\":\"学英语\",\"dayCount\":1}",
                registry.execute("createTodo", """
                {"title":"学英语","startDate":"2026-09-24","endDate":"2026-09-24"}
                """));

        ArgumentCaptor<TodoCreateDTO> captor = ArgumentCaptor.forClass(TodoCreateDTO.class);
        verify(toolService).createTodo(captor.capture());
        TodoCreateDTO dto = captor.getValue();
        assertEquals("学英语", dto.getTitle());
        assertEquals(LocalDate.of(2026, 9, 24), dto.getStartDate());
        assertEquals("#5c4b37", dto.getColor());
        assertEquals("学英语", dto.getDayContent());
        assertNull(dto.getWeekDays());
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesToolMetadataAndDoesNotRetryWrites() {
        ChatToolService toolService = mock(ChatToolService.class);
        when(toolService.createTodo(any(TodoCreateDTO.class)))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("timeout"));
        McpToolRegistry registry = new McpToolRegistry(toolService);
        registry.init();

        McpToolDefinition writeTool = registry.getTool("createTodo");
        assertFalse(writeTool.isReadOnly());
        assertEquals(0, writeTool.getRetryCount());
        McpToolRegistry.McpToolResult failure = registry.executeResult("createTodo", """
                {"title":"学英语","startDate":"2026-09-24","endDate":"2026-09-24"}
                """);
        assertFalse(failure.success());
        assertEquals("TOOL_EXECUTION_FAILED", failure.error().code());
        verify(toolService, times(1)).createTodo(any(TodoCreateDTO.class));

        Map<String, Object> dayTool = registry.toMcpToolList().stream()
                .filter(tool -> "queryDayDetail".equals(tool.get("name")))
                .findFirst().orElseThrow();
        Map<String, Object> metadata = (Map<String, Object>) dayTool.get("metadata");
        assertEquals(true, metadata.get("readOnly"));
        assertEquals(true, metadata.get("parallelSafe"));
        assertEquals(3000L, metadata.get("timeoutMs"));
    }

    @Test
    void rejectsMissingArgumentsWithoutCallingBusinessService() {
        ChatToolService toolService = mock(ChatToolService.class);
        McpToolRegistry registry = new McpToolRegistry(toolService);
        registry.init();

        McpToolRegistry.McpToolResult failure = registry.executeResult("queryDayDetail", "{}");
        assertFalse(failure.success());
        assertEquals("INVALID_ARGUMENTS", failure.error().code());
        org.mockito.Mockito.verifyNoInteractions(toolService);
    }

    @Test
    void returnsStructuredDayDetailAndBusinessFailure() {
        ChatToolService toolService = mock(ChatToolService.class);
        when(toolService.queryDayDetail("2026-09-25"))
                .thenReturn(new ChatToolService.DayDetailResult(
                        LocalDate.of(2026, 9, 25), List.of(), "今天休息"));
        when(toolService.deleteTodo(42L))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "待办不存在"));
        McpToolRegistry registry = new McpToolRegistry(toolService);
        registry.init();

        McpToolRegistry.McpToolResult day = registry.executeResult(
                "queryDayDetail", "{\"date\":\"2026-09-25\"}");
        assertTrue(day.success());
        assertEquals("{\"date\":\"2026-09-25\",\"todos\":[],\"dailyNote\":\"今天休息\"}",
                registry.execute("queryDayDetail", "{\"date\":\"2026-09-25\"}"));

        McpToolRegistry.McpToolResult failure = registry.executeResult(
                "deleteTodo", "{\"todoId\":42}");
        assertFalse(failure.success());
        assertEquals("BUSINESS_404", failure.error().code());
        assertFalse(failure.error().retryable());
    }

    @SuppressWarnings("unchecked")
    private Set<String> propertyNames(McpToolRegistry registry, String toolName) {
        Map<String, Object> properties = (Map<String, Object>)
                registry.getTool(toolName).getInputSchema().get("properties");
        return properties.keySet();
    }
}
