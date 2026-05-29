package com.qiniu.back.module.todo.service;

import com.qiniu.back.domain.event.dto.TodoCreateDTO;
import com.qiniu.back.domain.event.dto.TodoUpdateDTO;
import com.qiniu.back.domain.event.vo.TodoVO;

import java.util.List;

public interface TodoService {

    TodoVO create(TodoCreateDTO request);

    List<TodoVO> listByUser();

    TodoVO update(Long todoId, TodoUpdateDTO request);

    void delete(Long todoId);

    List<TodoVO> listByDate(String date);
}
