package com.qiniu.back.module.assistant.statemachine;

import com.qiniu.back.module.assistant.domain.result.PendingTask;
import java.util.function.Consumer;

/** 本轮已认领的上下文；写调用开始后发生异常时禁止自动释放认领。 */
public final class ConversationTurn {
    private final Long userId;
    private final String sessionId;
    private final String message;
    private final String task;
    private final Long dialogueId;
    private final ConversationStage stage;
    private final PendingTask pending;
    private final Consumer<String> progress;
    private boolean writeStarted;

    public ConversationTurn(Long userId, String sessionId, String message, String task, Long dialogueId,
                            ConversationStage stage, PendingTask pending, Consumer<String> progress) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.message = message;
        this.task = task == null || task.isBlank() ? message : task;
        this.dialogueId = dialogueId;
        this.stage = stage;
        this.pending = pending;
        this.progress = progress;
    }
    public Long userId() { return userId; }
    public String sessionId() { return sessionId; }
    public String message() { return message; }
    public String task() { return task; }
    public Long dialogueId() { return dialogueId; }
    public ConversationStage stage() { return stage; }
    public PendingTask pending() { return pending; }
    public Consumer<String> progress() { return progress; }
    public void markWriteStarted() { writeStarted = true; }
    public boolean writeStarted() { return writeStarted; }
}
