package com.qiniu.back.domain.chat.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class ChatHistoryItemVO {
    private Long dialogueId;
    private String role;
    private String content;
    private LocalDateTime createTime;
    private Long responseTimeMs;
}
