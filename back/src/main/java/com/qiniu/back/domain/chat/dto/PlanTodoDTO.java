package com.qiniu.back.domain.chat.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class PlanTodoDTO {
    private String title;
    private String dayContent;
    private LocalDate startDate;
    private LocalDate endDate;
    private List<Integer> weekDays;
    private String color;
    private String reason;
}
