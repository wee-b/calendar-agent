package com.qiniu.back.domain.todo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
@Schema(description = "修改待办请求")
public class TodoUpdateDTO {

    @NotBlank(message = "目标名称不能为空")
    @Schema(description = "目标名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "学英语")
    private String title;

    @Schema(description = "高亮颜色（hex）", example = "#2196F3")
    private String color;

    @Schema(description = "每日任务描述", example = "背50个单词并跟读课文")
    private String dayContent;

    @NotNull(message = "开始日期不能为空")
    @Schema(description = "开始日期", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-06-01")
    private LocalDate startDate;

    @NotNull(message = "结束日期不能为空")
    @Schema(description = "结束日期（距开始日期最多180天）", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-06-30")
    private LocalDate endDate;

    @NotNull(message = "执行日不能为空")
    @Size(min = 1, max = 7, message = "至少选择一天")
    @Schema(description = "每周执行日：1=周一 7=周日", requiredMode = Schema.RequiredMode.REQUIRED, example = "[1,3,5]")
    private List<Integer> weekDays;
}
