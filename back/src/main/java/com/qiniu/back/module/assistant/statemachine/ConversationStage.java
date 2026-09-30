package com.qiniu.back.module.assistant.statemachine;

/** 会话当前的主流程；临时闲聊、查询不会改变它。 */
public enum ConversationStage {
    CHAT,       // 无活动任务
    PLAN,       // 规划需求或草稿讨论中
    EXECUTE,    // 有待执行内容，等待确认或修改
    IMAGE       // 图片反馈中，仍关联原规划草稿
}
