package com.qiniu.back.domain.event.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TodoSaveDTO {
    private Long todoId;
    private String title;
    private String content;
    private LocalDateTime todoTime;
    private Integer priority;
    private String voiceText;
}
