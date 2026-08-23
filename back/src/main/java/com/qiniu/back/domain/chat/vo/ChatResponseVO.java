package com.qiniu.back.domain.chat.vo;

import lombok.Data;

@Data
public class ChatResponseVO {
    private String sessionId;
    private String aiResult;
    private String aiAudioUrl;
    private Long responseTimeMs;
    private String intent;
    private String executeResult;
    private Boolean needDispatchAgent;
    private String dispatchType;
    private String currentAgent;
    private String nextAgent;
    private String flowStage;
}
