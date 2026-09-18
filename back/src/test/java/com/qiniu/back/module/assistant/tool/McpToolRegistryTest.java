package com.qiniu.back.module.assistant.tool;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class McpToolRegistryTest {

    @Test
    void registersSingleDayCrudTools() {
        McpToolRegistry registry = new McpToolRegistry(mock(ChatToolService.class));
        registry.init();

        Set<String> names = registry.listTools().stream()
                .map(McpToolDefinition::getName)
                .collect(Collectors.toSet());

        assertTrue(names.containsAll(Set.of(
                "createSingleDayTodo", "queryDayDetail", "removeTodoDay", "updateTodoDay")));
    }
}
