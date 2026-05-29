package com.qiniu.back.module.todo.controller;

import com.qiniu.back.domain.ResponseDTO;
import com.qiniu.back.domain.event.dto.TodoCreateDTO;
import com.qiniu.back.domain.event.dto.TodoUpdateDTO;
import com.qiniu.back.domain.event.vo.TodoVO;
import com.qiniu.back.module.todo.service.TodoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "待办模块")
@RestController
@RequestMapping("/todo")
public class TodoController {

    @Autowired
    private TodoService todoService;

    @PostMapping
    @Operation(summary = "创建待办（目标）")
    public ResponseDTO<TodoVO> create(@RequestBody @Valid TodoCreateDTO request) {
        return ResponseDTO.ok(todoService.create(request));
    }

    @GetMapping("/list")
    @Operation(summary = "查询当前用户所有待办")
    public ResponseDTO<List<TodoVO>> list() {
        return ResponseDTO.ok(todoService.listByUser());
    }

    @PutMapping("/{todoId}")
    @Operation(summary = "修改待办")
    public ResponseDTO<TodoVO> update(@PathVariable Long todoId, @RequestBody @Valid TodoUpdateDTO request) {
        return ResponseDTO.ok(todoService.update(todoId, request));
    }

    @DeleteMapping("/{todoId}")
    @Operation(summary = "删除待办")
    public ResponseDTO<Void> delete(@PathVariable Long todoId) {
        todoService.delete(todoId);
        return ResponseDTO.ok();
    }
}
