package com.qiniu.back.module.chat.service;

import com.qiniu.back.domain.todo.dto.TodoCreateDTO;
import com.qiniu.back.domain.dailyNote.vo.DayTodosVO;
import com.qiniu.back.domain.dailyNote.vo.MonthCountVO;
import com.qiniu.back.module.dailyNote.service.DailyNoteService;
import com.qiniu.back.module.todo.service.TodoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
}
