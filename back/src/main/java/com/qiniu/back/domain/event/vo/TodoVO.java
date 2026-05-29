package com.qiniu.back.domain.event.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TodoVO {
    private Long todoId;
    private String title;
    private String content;
    private LocalDateTime todoTime;
    private Integer priority;
    private Integer status;
    private String voiceText;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
