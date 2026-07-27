package com.qiniu.back.domain.chat.vo;

import com.qiniu.back.domain.todo.vo.TodoVO;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class PlanSyncResultVO {
    private Long draftId;
    private String goal;
    private int createdCount;
    private List<TodoVO> createdTodos = new ArrayList<>();
}
