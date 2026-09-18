package com.qiniu.back.module.todo.service;

import com.qiniu.back.domain.todo.TodoDate;
import com.qiniu.back.domain.todo.dto.TodoCreateDTO;
import com.qiniu.back.domain.todo.dto.TodoUpdateDTO;
import com.qiniu.back.domain.todo.vo.TodoVO;

import java.time.LocalDate;
import java.util.List;

public interface TodoService {

    TodoVO create(TodoCreateDTO request);

    List<TodoVO> batchCreate(List<TodoCreateDTO> requests);

    List<TodoVO> listByUser();

    TodoVO update(Long todoId, TodoUpdateDTO request);

    void delete(Long todoId);

    List<TodoVO> listByDate(String date);

    int toggleDateStatus(Long todoId, LocalDate date);

    /** 从待办中移除某一天（不影响其他天），返回被移除的那天信息 */
    TodoDate removeTodoDay(Long todoId, LocalDate date);

    /** 给已有待办增加一天（用于补打卡、调日程），返回新增的 TodoDate */
    TodoDate addTodoDay(Long todoId, LocalDate date, String dayContent);
}
