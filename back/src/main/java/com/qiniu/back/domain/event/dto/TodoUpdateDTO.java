package com.qiniu.back.domain.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class TodoUpdateDTO {

    @NotBlank(message = "目标名称不能为空")
    private String title;

    private String color;

    private String dayContent;

    @NotNull(message = "开始日期不能为空")
    private LocalDate startDate;

    @NotNull(message = "结束日期不能为空")
    private LocalDate endDate;

    @NotNull(message = "执行日不能为空")
    @Size(min = 1, max = 7, message = "至少选择一天")
    private List<Integer> weekDays;
}
