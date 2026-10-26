package com.qiniu.back.domain.dailyNote.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "批量查询日期区间内的日程")
public class ScheduleRangeQueryDTO {
    @NotNull
    @Schema(description = "开始日期（包含）", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate startDate;

    @NotNull
    @Schema(description = "结束日期（包含），跨度不超过180天", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate endDate;
}
