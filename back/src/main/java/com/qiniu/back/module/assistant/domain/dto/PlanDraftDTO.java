package com.qiniu.back.module.assistant.domain.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class PlanDraftDTO {
    private String goal;
    private LocalDate startDate;
    private LocalDate endDate;
    private String analysis;
    private List<PlanTodoDTO> todos;
}
