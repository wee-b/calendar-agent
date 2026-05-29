package com.qiniu.back.domain.dailyNote.vo;

import lombok.Data;

import java.util.List;

@Data
public class DayTodosVO {
    private List<DayTodoItem> todos;
    private String dailyNote;

    @Data
    public static class DayTodoItem {
        private Long todoId;
        private String title;
        private String color;
        private String dayContent;
        private Integer status;
    }
}
