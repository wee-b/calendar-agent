package com.qiniu.back.module.todo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiniu.back.domain.ErrorCode;
import com.qiniu.back.domain.todo.Todo;
import com.qiniu.back.domain.todo.TodoDate;
import com.qiniu.back.domain.todo.dto.TodoCreateDTO;
import com.qiniu.back.domain.todo.dto.TodoUpdateDTO;
import com.qiniu.back.domain.todo.vo.TodoVO;
import com.qiniu.back.exception.BusinessException;
import com.qiniu.back.module.todo.mapper.TodoDateMapper;
import com.qiniu.back.module.todo.mapper.TodoMapper;
import com.qiniu.back.module.todo.service.TodoService;
import com.qiniu.back.util.LoginUserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TodoServiceImpl implements TodoService {

    private static final int MAX_DAYS = 180;

    @Autowired
    private TodoMapper todoMapper;

    @Autowired
    private TodoDateMapper todoDateMapper;

    @Override
    @Transactional
    public TodoVO create(TodoCreateDTO request) {
        Long userId = LoginUserContext.getUserId();
        validateDateRange(request.getStartDate(), request.getEndDate());

        // 1. 插入待办
        Todo todo = new Todo();
        todo.setUserId(userId);
        todo.setTitle(request.getTitle());
        todo.setColor(request.getColor() != null ? request.getColor() : "#5c4b37");
        todo.setStartDate(request.getStartDate());
        todo.setEndDate(request.getEndDate());
        todo.setWeekDays(toWeekDaysStr(request.getWeekDays()));
        todoMapper.insert(todo);

        // 2. 批量插入每日任务
        List<LocalDate> dates = calcDates(request.getStartDate(), request.getEndDate(), request.getWeekDays());
        batchInsertTodoDates(todo.getTodoId(), dates, request.getDayContent());

        return toTodoVO(todo, dates);
    }

    @Override
    public List<TodoVO> listByUser() {
        Long userId = LoginUserContext.getUserId();
        List<Todo> todos = todoMapper.selectList(new LambdaQueryWrapper<Todo>()
                .eq(Todo::getUserId, userId)
                .orderByDesc(Todo::getCreateTime));

        // 批量查询所有待办的日期，避免 N+1
        List<Long> todoIds = todos.stream().map(Todo::getTodoId).toList();
        Map<Long, List<LocalDate>> dateMap = Map.of();
        if (!todoIds.isEmpty()) {
            dateMap = todoDateMapper.selectList(
                    new LambdaQueryWrapper<TodoDate>()
                            .in(TodoDate::getTodoId, todoIds))
                    .stream()
                    .collect(Collectors.groupingBy(
                            TodoDate::getTodoId,
                            Collectors.mapping(TodoDate::getTodoDate, Collectors.toList())));
        }
        final Map<Long, List<LocalDate>> finalDateMap = dateMap;

        return todos.stream().map(todo -> {
            List<LocalDate> dates = finalDateMap.getOrDefault(todo.getTodoId(), List.of());
            return toTodoVO(todo, dates);
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public TodoVO update(Long todoId, TodoUpdateDTO request) {
        Todo todo = todoMapper.selectById(todoId);
        Long userId = LoginUserContext.getUserId();
        if (todo == null || !todo.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "待办不存在");
        }
        validateDateRange(request.getStartDate(), request.getEndDate());

        // 1. 更新待办基本信息
        todo.setTitle(request.getTitle());
        todo.setColor(request.getColor() != null ? request.getColor() : todo.getColor());
        todo.setStartDate(request.getStartDate());
        todo.setEndDate(request.getEndDate());
        todo.setWeekDays(toWeekDaysStr(request.getWeekDays()));
        todoMapper.updateById(todo);

        // 2. 删除旧的每日任务
        todoDateMapper.delete(new LambdaQueryWrapper<TodoDate>().eq(TodoDate::getTodoId, todoId));

        // 3. 重新批量插入
        List<LocalDate> dates = calcDates(request.getStartDate(), request.getEndDate(), request.getWeekDays());
        batchInsertTodoDates(todoId, dates, request.getDayContent());

        return toTodoVO(todo, dates);
    }

    @Override
    @Transactional
    public void delete(Long todoId) {
        Long userId = LoginUserContext.getUserId();
        Todo todo = todoMapper.selectById(todoId);
        if (todo == null || !todo.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "待办不存在");
        }
        // 删除每日任务
        todoDateMapper.delete(new LambdaQueryWrapper<TodoDate>().eq(TodoDate::getTodoId, todoId));
        // 物理删除待办
        todoMapper.delete(new LambdaQueryWrapper<Todo>().eq(Todo::getTodoId, todoId));
    }

    @Override
    public int toggleDateStatus(Long todoId, LocalDate date) {
        Long userId = LoginUserContext.getUserId();
        Todo todo = todoMapper.selectById(todoId);
        if (todo == null || !todo.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "待办不存在");
        }

        TodoDate todoDate = todoDateMapper.selectOne(new LambdaQueryWrapper<TodoDate>()
                .eq(TodoDate::getTodoId, todoId)
                .eq(TodoDate::getTodoDate, date));
        if (todoDate == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该日期没有对应的任务");
        }

        int newStatus = todoDate.getStatus() == 1 ? 0 : 1;
        todoDate.setStatus(newStatus);
        todoDateMapper.updateById(todoDate);

        // 检查该待办下所有日期是否都已完成，若是则自动完成整个待办
        if (newStatus == 1) {
            Long unfinishedCount = todoDateMapper.selectCount(new LambdaQueryWrapper<TodoDate>()
                    .eq(TodoDate::getTodoId, todoId)
                    .eq(TodoDate::getStatus, 0));
            if (unfinishedCount == 0) {
                todo.setStatus(1);
                todoMapper.updateById(todo);
            }
        } else {
            // 有日期被取消完成，待办状态也恢复为未完成
            if (todo.getStatus() == 1) {
                todo.setStatus(0);
                todoMapper.updateById(todo);
            }
        }

        return newStatus;
    }

    @Override
    @Transactional
    public TodoDate removeTodoDay(Long todoId, LocalDate date) {
        Long userId = LoginUserContext.getUserId();
        Todo todo = todoMapper.selectById(todoId);
        if (todo == null || !todo.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "待办不存在");
        }

        TodoDate todoDate = todoDateMapper.selectOne(new LambdaQueryWrapper<TodoDate>()
                .eq(TodoDate::getTodoId, todoId)
                .eq(TodoDate::getTodoDate, date));
        if (todoDate == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该日期 (" + date + ") 没有对应的任务记录");
        }

        todoDateMapper.deleteById(todoDate.getId());
        return todoDate;
    }

    @Override
    @Transactional
    public TodoDate addTodoDay(Long todoId, LocalDate date, String dayContent) {
        Long userId = LoginUserContext.getUserId();
        Todo todo = todoMapper.selectById(todoId);
        if (todo == null || !todo.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "待办不存在");
        }

        // 检查该天是否已存在
        TodoDate existing = todoDateMapper.selectOne(new LambdaQueryWrapper<TodoDate>()
                .eq(TodoDate::getTodoId, todoId)
                .eq(TodoDate::getTodoDate, date));
        if (existing != null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "该日期 (" + date + ") 已存在任务记录，无需重复添加");
        }

        TodoDate td = new TodoDate();
        td.setTodoId(todoId);
        td.setTodoDate(date);
        td.setDayContent(dayContent);
        td.setStatus(0);
        todoDateMapper.insert(td);

        return td;
    }

    @Override
    public List<TodoVO> listByDate(String date) {
        return null; // DayTodosVO 在 DailyNoteService 中组装
    }

    // ==================== 私有工具方法 ====================

    private void validateDateRange(LocalDate start, LocalDate end) {
        if (start.isAfter(end)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "开始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "日期跨度不能超过" + MAX_DAYS + "天");
        }
    }

    private String toWeekDaysStr(List<Integer> weekDays) {
        return weekDays.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    private List<Integer> parseWeekDays(String weekDaysStr) {
        return Arrays.stream(weekDaysStr.split(",")).map(Integer::parseInt).collect(Collectors.toList());
    }

    /**
     * 根据开始日期、结束日期、每周执行日，计算所有需要插入的日期
     */
    private List<LocalDate> calcDates(LocalDate start, LocalDate end, List<Integer> weekDays) {
        Set<Integer> daySet = new HashSet<>(weekDays);
        List<LocalDate> dates = new ArrayList<>();
        LocalDate current = start;
        while (!current.isAfter(end)) {
            int dow = current.getDayOfWeek().getValue(); // 1=Mon, 7=Sun
            if (daySet.contains(dow)) {
                dates.add(current);
            }
            current = current.plusDays(1);
        }
        return dates;
    }

    private void batchInsertTodoDates(Long todoId, List<LocalDate> dates, String dayContent) {
        if (dates.isEmpty()) return;
        List<TodoDate> entities = new ArrayList<>(dates.size());
        for (LocalDate date : dates) {
            TodoDate td = new TodoDate();
            td.setTodoId(todoId);
            td.setTodoDate(date);
            td.setDayContent(dayContent);
            td.setStatus(0);
            entities.add(td);
        }
        todoDateMapper.insertBatch(entities);
    }

    private TodoVO toTodoVO(Todo todo, List<LocalDate> dates) {
        TodoVO vo = new TodoVO();
        vo.setTodoId(todo.getTodoId());
        vo.setTitle(todo.getTitle());
        vo.setColor(todo.getColor());
        vo.setStartDate(todo.getStartDate());
        vo.setEndDate(todo.getEndDate());
        vo.setWeekDays(parseWeekDays(todo.getWeekDays()));
        vo.setPriority(todo.getPriority());
        vo.setStatus(todo.getStatus());
        vo.setVoiceText(todo.getVoiceText());
        vo.setDates(dates);
        vo.setCreateTime(todo.getCreateTime());
        vo.setUpdateTime(todo.getUpdateTime());
        return vo;
    }
}
