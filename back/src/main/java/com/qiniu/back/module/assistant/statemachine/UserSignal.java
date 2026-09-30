package com.qiniu.back.module.assistant.statemachine;

/** Route Agent 对本轮输入的唯一分类；模型不能指定处理 Agent 或下一状态。 */
public enum UserSignal {
    NEW_CHAT, NEW_QUERY, NEW_PLAN, NEW_EXECUTE,
    CONFIRM, REJECT, MODIFY, UNKNOWN, SYNC_PLAN, GENERATE_PLAN_IMAGE
}
