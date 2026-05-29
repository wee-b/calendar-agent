package com.qiniu.back.module.dailyNote.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiniu.back.domain.dailyNote.DailyNote;
import com.qiniu.back.domain.todo.Todo;
import com.qiniu.back.domain.todo.TodoDate;
import com.qiniu.back.domain.dailyNote.dto.DailyNoteSaveDTO;
import com.qiniu.back.domain.dailyNote.vo.DayTodosVO;
import com.qiniu.back.domain.dailyNote.vo.MonthCountVO;
import com.qiniu.back.module.dailyNote.mapper.DailyNoteMapper;
import com.qiniu.back.module.todo.mapper.TodoDateMapper;
import com.qiniu.back.module.todo.mapper.TodoMapper;
import com.qiniu.back.module.dailyNote.service.DailyNoteService;
import com.qiniu.back.util.LoginUserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DailyNoteServiceImpl implements DailyNoteService {

    @Autowired
    private TodoDateMapper todoDateMapper;

    @Autowired
    private TodoMapper todoMapper;

    @Autowired
    private DailyNoteMapper dailyNoteMapper;

    @Override
    public List<MonthCountVO> getMonthCount(int year, int month) {
        Long userId = LoginUserContext.getUserId();
        YearMonth ym = YearMonth.of(year, month);
        LocalDate firstDay = ym.atDay(1);
        LocalDate lastDay = ym.atEndOfMonth();

        // 查该用户在当月所有的待办日期
        List<TodoDate> todoDates = todoDateMapper.selectList(
                new LambdaQueryWrapper<TodoDate>()
                        .ge(TodoDate::getTodoDate, firstDay)
                        .le(TodoDate::getTodoDate, lastDay));

        // 过滤掉已删除的待办
        List<Long> todoIds = todoDates.stream()
                .map(TodoDate::getTodoId).distinct().collect(Collectors.toList());
        if (todoIds.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Long> validTodoIds = todoMapper.selectList(
                new LambdaQueryWrapper<Todo>()
                        .in(Todo::getTodoId, todoIds)
                        .eq(Todo::getUserId, userId)
                        .eq(Todo::getDeletedFlag, 0))
                .stream().map(Todo::getTodoId).collect(Collectors.toSet());

        // 按日期分组统计
        Map<LocalDate, Long> countMap = todoDates.stream()
                .filter(td -> validTodoIds.contains(td.getTodoId()))
                .collect(Collectors.groupingBy(TodoDate::getTodoDate, Collectors.counting()));

        return countMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new MonthCountVO(e.getKey(), e.getValue().intValue()))
                .collect(Collectors.toList());
    }

    @Override
    public DayTodosVO getDayDetail(String date) {
        Long userId = LoginUserContext.getUserId();
        LocalDate targetDate = LocalDate.parse(date);

        // 1. 查当天的所有待办日期记录
        List<TodoDate> todoDates = todoDateMapper.selectList(
                new LambdaQueryWrapper<TodoDate>()
                        .eq(TodoDate::getTodoDate, targetDate));
        if (todoDates.isEmpty()) {
            DayTodosVO vo = new DayTodosVO();
            vo.setTodos(new ArrayList<>());
            vo.setDailyNote(getDailyNoteContent(targetDate));
            return vo;
        }

        // 2. 查对应的待办（过滤已删除 + 非当前用户）
        List<Long> todoIds = todoDates.stream().map(TodoDate::getTodoId).collect(Collectors.toList());
        List<Todo> todos = todoMapper.selectList(
                new LambdaQueryWrapper<Todo>()
                        .in(Todo::getTodoId, todoIds)
                        .eq(Todo::getUserId, userId)
                        .eq(Todo::getDeletedFlag, 0));
        Map<Long, Todo> todoMap = todos.stream().collect(Collectors.toMap(Todo::getTodoId, t -> t));

        List<DayTodosVO.DayTodoItem> items = new ArrayList<>();
        for (TodoDate td : todoDates) {
            Todo todo = todoMap.get(td.getTodoId());
            if (todo == null) continue;
            DayTodosVO.DayTodoItem item = new DayTodosVO.DayTodoItem();
            item.setTodoId(todo.getTodoId());
            item.setTitle(todo.getTitle());
            item.setColor(todo.getColor());
            item.setDayContent(td.getDayContent());
            item.setStatus(td.getStatus());
            items.add(item);
        }

        DayTodosVO vo = new DayTodosVO();
        vo.setTodos(items);
        vo.setDailyNote(getDailyNoteContent(targetDate));
        return vo;
    }

    @Override
    @Transactional
    public void saveDailyNote(DailyNoteSaveDTO request) {
        Long userId = LoginUserContext.getUserId();
        // upsert: 有则更新，无则插入
        DailyNote existing = dailyNoteMapper.selectOne(
                new LambdaQueryWrapper<DailyNote>()
                        .eq(DailyNote::getUserId, userId)
                        .eq(DailyNote::getNoteDate, request.getNoteDate()));

        if (existing != null) {
            existing.setContent(request.getContent());
            dailyNoteMapper.updateById(existing);
        } else {
            DailyNote note = new DailyNote();
            note.setUserId(userId);
            note.setNoteDate(request.getNoteDate());
            note.setContent(request.getContent());
            dailyNoteMapper.insert(note);
        }
    }

    private String getDailyNoteContent(LocalDate date) {
        Long userId = LoginUserContext.getUserId();
        DailyNote note = dailyNoteMapper.selectOne(
                new LambdaQueryWrapper<DailyNote>()
                        .eq(DailyNote::getUserId, userId)
                        .eq(DailyNote::getNoteDate, date));
        return note != null ? note.getContent() : null;
    }
}
