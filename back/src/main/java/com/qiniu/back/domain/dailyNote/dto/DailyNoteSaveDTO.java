package com.qiniu.back.domain.dailyNote.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "保存日记请求")
public class DailyNoteSaveDTO {

    @NotNull(message = "日期不能为空")
    @Schema(description = "日记日期", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-06-01")
    private LocalDate noteDate;

    @Schema(description = "日记内容", example = "今天完成了所有任务，感觉不错！")
    private String content;
}
