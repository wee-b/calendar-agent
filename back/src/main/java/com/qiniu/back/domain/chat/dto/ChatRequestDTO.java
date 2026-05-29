package com.qiniu.back.domain.chat.dto;

import lombok.Data;

@Data
public class ChatRequestDTO {
    private String sessionId;
    private String message;
}
