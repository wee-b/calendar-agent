package com.qiniu.back.domain.todo.vo;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class TodoVO {
    private Long todoId;
    private String title;
    private String color;
    private LocalDate startDate;
    private LocalDate endDate;
    private List<Integer> weekDays;
    private Integer priority;
    private Integer status;
    private String voiceText;
    private List<LocalDate> dates;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
