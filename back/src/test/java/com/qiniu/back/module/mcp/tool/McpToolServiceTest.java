package com.qiniu.back.module.mcp.tool;

import com.qiniu.back.domain.dailyNote.vo.DayTodosVO;
import com.qiniu.back.domain.todo.dto.TodoCreateDTO;
import com.qiniu.back.domain.todo.vo.TodoVO;
import com.qiniu.back.exception.BusinessException;
import com.qiniu.back.module.dailyNote.service.DailyNoteService;
import com.qiniu.back.module.todo.service.TodoService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class McpToolServiceTest {

    @Test
    void batchCreateDelegatesOnceToTransactionalBusinessService() {
        TodoService todoService = mock(TodoService.class);
        TodoCreateDTO request = new TodoCreateDTO();
        request.setTitle("学习");
        request.setStartDate(LocalDate.of(2026, 10, 3));
        request.setEndDate(request.getStartDate());
        TodoVO created = new TodoVO();
        created.setTodoId(5L);
        created.setTitle("学习");
        created.setStartDate(request.getStartDate());
        created.setEndDate(request.getEndDate());
        List<TodoCreateDTO> requests = List.of(request);
        when(todoService.batchCreate(requests)).thenReturn(List.of(created));

        var result = service(todoService, mock(DailyNoteService.class)).batchCreateTodos(requests);

        assertEquals(1, result.createdCount());
        assertEquals(5L, result.createdTodos().get(0).todoId());
        verify(todoService).batchCreate(requests);
        verifyNoMoreInteractions(todoService);
    }

    @Test
    void validatesWholeBatchBeforeAnyBusinessWrite() {
        TodoService todoService = mock(TodoService.class);
        McpToolService service = service(todoService, mock(DailyNoteService.class));
        assertThrows(BusinessException.class, () -> service.batchCreateTodos(List.of()));
        assertThrows(BusinessException.class, () -> service.batchCreateTodos(List.of(new TodoCreateDTO())));
        org.mockito.Mockito.verifyNoInteractions(todoService);
    }

    @Test
    void dayDetailKeepsNoteEvenWhenThereAreNoTodos() {
        DailyNoteService dailyNoteService = mock(DailyNoteService.class);
        McpToolService service = service(mock(TodoService.class), dailyNoteService);
        DayTodosVO detail = new DayTodosVO();
        detail.setTodos(List.of());
        detail.setDailyNote("今天休息");
        when(dailyNoteService.getDayDetail("2026-09-25")).thenReturn(detail);

        McpToolService.DayDetailResult result = service.queryDayDetail("2026-09-25");

        assertEquals(LocalDate.of(2026, 9, 25), result.date());
        assertEquals(List.of(), result.todos());
        assertEquals("今天休息", result.dailyNote());
    }

    @Test
    void missingTodoIsAnErrorAndDoesNotDeleteAnything() {
        TodoService todoService = mock(TodoService.class);
        McpToolService service = service(todoService, mock(DailyNoteService.class));
        when(todoService.listByUser()).thenReturn(List.of());

        BusinessException error = assertThrows(BusinessException.class, () -> service.deleteTodo(42L));

        assertEquals(404, error.getCode());
        verify(todoService).listByUser();
        verifyNoMoreInteractions(todoService);
    }

    private McpToolService service(TodoService todoService, DailyNoteService dailyNoteService) {
        McpToolService service = new McpToolService();
        ReflectionTestUtils.setField(service, "todoService", todoService);
        ReflectionTestUtils.setField(service, "dailyNoteService", dailyNoteService);
        return service;
    }
}
