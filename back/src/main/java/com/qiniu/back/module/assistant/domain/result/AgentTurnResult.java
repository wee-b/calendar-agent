package com.qiniu.back.module.assistant.domain.result;

/** accepted=false 表示缺少必要上下文，本轮回复后保留原阶段与产物。 */
public record AgentTurnResult(
        String reply, String dispatchType, boolean dispatched, boolean accepted, PendingTask pending) {
    public static AgentTurnResult success(String reply, String type, boolean dispatched, PendingTask pending) {
        return new AgentTurnResult(reply, type, dispatched, true, pending);
    }
    public static AgentTurnResult unchanged(String reply, String type, PendingTask pending) {
        return new AgentTurnResult(reply, type, false, false, pending);
    }
}
