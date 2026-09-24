package com.qiniu.back.domain.dailyNote.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "按月查询待办数量请求")
public class MonthCountQueryDTO {

    @NotNull(message = "年份不能为空")
    @Schema(description = "年份", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026")
    private Integer year;

    @NotNull(message = "月份不能为空")
    @Min(value = 1, message = "月份不能小于1")
    @Max(value = 12, message = "月份不能大于12")
    @Schema(description = "月份（1-12）", requiredMode = Schema.RequiredMode.REQUIRED, example = "9")
    private Integer month;
}
