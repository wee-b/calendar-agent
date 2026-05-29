package com.qiniu.back.domain.todo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "完成/取消完成每日任务请求")
public class TodoDateToggleDTO {

    @NotNull(message = "待办ID不能为空")
    @Schema(description = "待办ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long todoId;

    @NotNull(message = "日期不能为空")
    @Schema(description = "任务日期", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-06-01")
    private LocalDate todoDate;
}
