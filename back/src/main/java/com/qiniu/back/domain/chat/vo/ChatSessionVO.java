package com.qiniu.back.domain.chat.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class ChatSessionVO {
    private String sessionId;
    private String title;
    private LocalDateTime createTime;
    private int messageCount;
}
