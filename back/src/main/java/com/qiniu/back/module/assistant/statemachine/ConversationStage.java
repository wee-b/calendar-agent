package com.qiniu.back.module.assistant.statemachine;

/**
 * 状态描述的是业务流程，不是当前由哪个 Agent 处理。
 */
public enum ConversationStage {
    READY_FOR_INPUT, // 等待新任务
    AWAITING_EXECUTION_CONFIRMATION,  // 等待执行确认
    AWAITING_PLAN_CONFIRMATION,   // 等待规划确认
    AWAITING_PLAN_FEEDBACK   // 等待规划反馈
}
