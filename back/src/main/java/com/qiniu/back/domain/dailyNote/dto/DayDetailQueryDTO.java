package com.qiniu.back.domain.dailyNote.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "按日期查询日程详情请求")
public class DayDetailQueryDTO {

    @NotNull(message = "日期不能为空")
    @Schema(description = "查询日期", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-09-24")
    private LocalDate date;
}
