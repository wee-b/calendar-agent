package com.qiniu.back.module.assistant.tool;

import com.qiniu.back.domain.dailyNote.vo.DayTodosVO;
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

class ChatToolServiceTest {

    @Test
    void dayDetailKeepsNoteEvenWhenThereAreNoTodos() {
        DailyNoteService dailyNoteService = mock(DailyNoteService.class);
        ChatToolService service = service(mock(TodoService.class), dailyNoteService);
        DayTodosVO detail = new DayTodosVO();
        detail.setTodos(List.of());
        detail.setDailyNote("今天休息");
        when(dailyNoteService.getDayDetail("2026-09-25")).thenReturn(detail);

        ChatToolService.DayDetailResult result = service.queryDayDetail("2026-09-25");

        assertEquals(LocalDate.of(2026, 9, 25), result.date());
        assertEquals(List.of(), result.todos());
        assertEquals("今天休息", result.dailyNote());
    }

    @Test
    void missingTodoIsAnErrorAndDoesNotDeleteAnything() {
        TodoService todoService = mock(TodoService.class);
        ChatToolService service = service(todoService, mock(DailyNoteService.class));
        when(todoService.listByUser()).thenReturn(List.of());

        BusinessException error = assertThrows(BusinessException.class, () -> service.deleteTodo(42L));

        assertEquals(404, error.getCode());
        verify(todoService).listByUser();
        verifyNoMoreInteractions(todoService);
    }

    private ChatToolService service(TodoService todoService, DailyNoteService dailyNoteService) {
        ChatToolService service = new ChatToolService();
        ReflectionTestUtils.setField(service, "todoService", todoService);
        ReflectionTestUtils.setField(service, "dailyNoteService", dailyNoteService);
        return service;
    }
}
