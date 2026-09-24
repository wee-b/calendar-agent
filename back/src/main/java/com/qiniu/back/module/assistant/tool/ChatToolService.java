package com.qiniu.back.module.assistant.tool;

import com.qiniu.back.domain.ErrorCode;
import com.qiniu.back.domain.dailyNote.dto.DailyNoteSaveDTO;
import com.qiniu.back.domain.dailyNote.vo.DayTodosVO;
import com.qiniu.back.domain.dailyNote.vo.MonthCountVO;
import com.qiniu.back.domain.todo.dto.TodoCreateDTO;
import com.qiniu.back.domain.todo.dto.TodoUpdateDTO;
import com.qiniu.back.domain.todo.vo.TodoVO;
import com.qiniu.back.exception.BusinessException;
import com.qiniu.back.module.dailyNote.service.DailyNoteService;
import com.qiniu.back.module.todo.service.TodoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Component
public class ChatToolService {

    public record DayDetailResult(LocalDate date, List<DayTodosVO.DayTodoItem> todos, String dailyNote) {}
    public record MonthCountResult(int year, int month, List<MonthCountVO> days) {}
    public record TodoListResult(List<TodoVO> todos) {}
    public record TodoMutationResult(String operation, Long todoId, String title, Integer dayCount) {}
    public record TodoDateResult(String operation, Long todoId, LocalDate date, Integer status) {}
    public record DailyNoteResult(LocalDate date, boolean saved) {}

    @Autowired
    private TodoService todoService;

    @Autowired
    private DailyNoteService dailyNoteService;

    public TodoMutationResult createTodo(TodoCreateDTO dto) {
        log.info("Tool-createTodo: {}", dto.getTitle());
        TodoVO vo = todoService.create(dto);
        return new TodoMutationResult("created", vo.getTodoId(), vo.getTitle(), vo.getDates().size());
    }

    public MonthCountResult queryMonthCount(int year, int month) {
        log.info("Tool-queryMonthCount: {}-{}", year, month);
        return new MonthCountResult(year, month, dailyNoteService.getMonthCount(year, month));
    }

    public DayDetailResult queryDayDetail(String date) {
        log.info("Tool-queryDayDetail: {}", date);
        DayTodosVO vo = dailyNoteService.getDayDetail(date);
        return new DayDetailResult(LocalDate.parse(date), vo.getTodos(), vo.getDailyNote());
    }

    public TodoListResult queryTodoList() {
        log.info("Tool-queryTodoList");
        return new TodoListResult(todoService.listByUser());
    }

    public TodoMutationResult deleteTodo(Long todoId) {
        log.info("Tool-deleteTodo: {}", todoId);
        TodoVO target = findTodo(todoId);
        todoService.delete(todoId);
        return new TodoMutationResult("deleted", todoId, target.getTitle(), null);
    }

    public TodoMutationResult updateTodo(Long todoId, TodoUpdateDTO dto) {
        log.info("Tool-updateTodo: {}", todoId);
        TodoVO vo = todoService.update(todoId, dto);
        return new TodoMutationResult("updated", vo.getTodoId(), vo.getTitle(), vo.getDates().size());
    }

    public TodoDateResult toggleTodoDate(Long todoId, String date) {
        log.info("Tool-toggleTodoDate: todoId={}, date={}", todoId, date);
        LocalDate parsedDate = LocalDate.parse(date);
        int newStatus = todoService.toggleDateStatus(todoId, parsedDate);
        return new TodoDateResult("toggled", todoId, parsedDate, newStatus);
    }

    public TodoDateResult removeTodoDay(Long todoId, String date) {
        log.info("Tool-removeTodoDay: todoId={}, date={}", todoId, date);
        LocalDate parsedDate = LocalDate.parse(date);
        todoService.removeTodoDay(todoId, parsedDate);
        return new TodoDateResult("removed", todoId, parsedDate, null);
    }

    public TodoDateResult addTodoDay(Long todoId, String date, String dayContent) {
        log.info("Tool-addTodoDay: todoId={}, date={}", todoId, date);
        LocalDate parsedDate = LocalDate.parse(date);
        todoService.addTodoDay(todoId, parsedDate, dayContent);
        return new TodoDateResult("added", todoId, parsedDate, null);
    }

    public DailyNoteResult saveDailyNote(String date, String content) {
        log.info("Tool-saveDailyNote: date={}", date);
        LocalDate parsedDate = LocalDate.parse(date);
        DailyNoteSaveDTO dto = new DailyNoteSaveDTO();
        dto.setNoteDate(parsedDate);
        dto.setContent(content);
        dailyNoteService.saveDailyNote(dto);
        return new DailyNoteResult(parsedDate, true);
    }

    private TodoVO findTodo(Long todoId) {
        return todoService.listByUser().stream()
                .filter(todo -> todo.getTodoId().equals(todoId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "未找到 ID=" + todoId + " 的待办"));
    }
}
