package com.qiniu.back.module.todo.service;

import com.qiniu.back.domain.todo.dto.TodoCreateDTO;
import com.qiniu.back.domain.todo.dto.TodoUpdateDTO;
import com.qiniu.back.domain.todo.vo.TodoVO;

import java.time.LocalDate;
import java.util.List;

public interface TodoService {

    TodoVO create(TodoCreateDTO request);

    List<TodoVO> listByUser();

    TodoVO update(Long todoId, TodoUpdateDTO request);

    void delete(Long todoId);

    List<TodoVO> listByDate(String date);

    int toggleDateStatus(Long todoId, LocalDate date);
}
