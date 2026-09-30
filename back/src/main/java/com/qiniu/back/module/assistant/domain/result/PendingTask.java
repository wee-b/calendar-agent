package com.qiniu.back.module.assistant.domain.result;

/** 会话产物；图片只改变 imageInstruction，保留原规划 ID 和正文。 */
public record PendingTask(String task, Long draftId, String planPreview, String imageInstruction) {
    public static PendingTask empty() { return new PendingTask(null, null, null, null); }
    public static PendingTask instruction(String task) { return new PendingTask(task, null, null, null); }
}
