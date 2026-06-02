package com.qiniu.back.module.chat.service;

import com.qiniu.back.domain.todo.dto.TodoCreateDTO;
import com.qiniu.back.domain.todo.dto.TodoUpdateDTO;
import com.qiniu.back.domain.todo.vo.TodoVO;
import com.qiniu.back.domain.dailyNote.dto.DailyNoteSaveDTO;
import com.qiniu.back.domain.dailyNote.vo.DayTodosVO;
import com.qiniu.back.domain.dailyNote.vo.MonthCountVO;
import com.qiniu.back.module.dailyNote.service.DailyNoteService;
import com.qiniu.back.module.todo.service.TodoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ChatToolService {

    @Autowired
    private TodoService todoService;

    @Autowired
    private DailyNoteService dailyNoteService;

    public String createTodo(TodoCreateDTO dto) {
        log.info("Tool-createTodo: {}", dto.getTitle());
        var vo = todoService.create(dto);
        return "已创建目标：" + vo.getTitle() + "，颜色" + vo.getColor() + "，共" + vo.getDates().size() + "天。";
    }

    public String queryMonthCount(int year, int month) {
        log.info("Tool-queryMonthCount: {}-{}", year, month);
        List<MonthCountVO> counts = dailyNoteService.getMonthCount(year, month);
        if (counts.isEmpty()) {
            return year + "年" + month + "月暂无待办。";
        }
        return counts.stream()
                .map(c -> c.getDate() + ": " + c.getCount() + "个待办")
                .collect(Collectors.joining(", "));
    }

    public String queryDayDetail(String date) {
        log.info("Tool-queryDayDetail: {}", date);
        DayTodosVO vo = dailyNoteService.getDayDetail(date);
        if (vo.getTodos().isEmpty()) {
            return date + " 暂无待办。";
        }
        String todoStr = vo.getTodos().stream()
                .map(t -> (t.getStatus() == 1 ? "[✓]" : "[ ]") + t.getTitle()
                        + (t.getDayContent() != null ? " - " + t.getDayContent() : ""))
                .collect(Collectors.joining("; "));
        return date + " 待办: " + todoStr + "。";
    }

    public String queryTodoList() {
        log.info("Tool-queryTodoList");
        List<TodoVO> todos = todoService.listByUser();
        if (todos.isEmpty()) {
            return "你目前没有待办目标。";
        }
        return todos.stream()
                .map(t -> "[" + t.getTodoId() + "] " + t.getTitle()
                        + "（" + t.getStartDate() + " ~ " + t.getEndDate()
                        + "，状态" + (t.getStatus() == 1 ? "已完成" : "进行中")
                        + "，" + t.getDates().size() + "天"
                        + "，创建于" + t.getCreateTime() + "）")
                .collect(Collectors.joining("; "));
    }

    public String deleteTodo(Long todoId) {
        log.info("Tool-deleteTodo: {}", todoId);
        // 先查询确认待办存在
        List<TodoVO> todos = todoService.listByUser();
        TodoVO target = todos.stream()
                .filter(t -> t.getTodoId().equals(todoId))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return "删除失败：未找到 ID=" + todoId + " 的待办，可能已被删除或不存在。请重新查询待办列表获取最新数据。";
        }
        todoService.delete(todoId);
        return "已删除待办【" + target.getTitle() + "】ID=" + todoId + "。请调用 queryTodoList 验证删除结果。";
    }

    public String updateTodo(Long todoId, TodoUpdateDTO dto) {
        log.info("Tool-updateTodo: {}", todoId);
        TodoVO vo = todoService.update(todoId, dto);
        return "已更新目标：" + vo.getTitle() + "，颜色" + vo.getColor() + "，共" + vo.getDates().size() + "天。";
    }

    public String toggleTodoDate(Long todoId, String date) {
        log.info("Tool-toggleTodoDate: todoId={}, date={}", todoId, date);
        int newStatus = todoService.toggleDateStatus(todoId, LocalDate.parse(date));
        String statusText = newStatus == 1 ? "已完成" : "取消完成";
        return "待办 ID=" + todoId + " 在 " + date + " " + statusText + "。";
    }

    public String removeTodoDay(Long todoId, String date) {
        log.info("Tool-removeTodoDay: todoId={}, date={}", todoId, date);
        List<TodoVO> todos = todoService.listByUser();
        TodoVO target = todos.stream()
                .filter(t -> t.getTodoId().equals(todoId))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return "移除失败：未找到 ID=" + todoId + " 的待办，可能已被删除。请调用 queryTodoList 获取最新数据。";
        }
        try {
            todoService.removeTodoDay(todoId, LocalDate.parse(date));
            return "已从【" + target.getTitle() + "】中移除 " + date + "，其他天不受影响。请调用 queryDayDetail 验证。";
        } catch (Exception e) {
            return "移除失败: " + e.getMessage();
        }
    }

    public String addTodoDay(Long todoId, String date, String dayContent) {
        log.info("Tool-addTodoDay: todoId={}, date={}, content={}", todoId, date, dayContent);
        List<TodoVO> todos = todoService.listByUser();
        TodoVO target = todos.stream()
                .filter(t -> t.getTodoId().equals(todoId))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return "添加失败：未找到 ID=" + todoId + " 的待办。请调用 queryTodoList 获取最新数据。";
        }
        try {
            todoService.addTodoDay(todoId, LocalDate.parse(date), dayContent);
            return "已给【" + target.getTitle() + "】增加 " + date + " 这一天。请调用 queryDayDetail 验证。";
        } catch (Exception e) {
            return "添加失败: " + e.getMessage();
        }
    }

    public String saveDailyNote(String date, String content) {
        log.info("Tool-saveDailyNote: date={}", date);
        DailyNoteSaveDTO dto = new DailyNoteSaveDTO();
        dto.setNoteDate(LocalDate.parse(date));
        dto.setContent(content);
        dailyNoteService.saveDailyNote(dto);
        return "已保存" + date + "的日记。";
    }
}
