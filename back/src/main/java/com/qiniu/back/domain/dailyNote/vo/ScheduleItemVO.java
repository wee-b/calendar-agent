package com.qiniu.back.domain.dailyNote.vo;

import lombok.Data;

import java.time.LocalDate;

@Data
public class ScheduleItemVO {
    private Long todoId;
    private String title;
    private LocalDate date;
    private String dayContent;
    private Integer status;
}
