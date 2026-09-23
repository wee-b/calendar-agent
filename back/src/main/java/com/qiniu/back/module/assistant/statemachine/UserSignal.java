package com.qiniu.back.module.assistant.statemachine;

public enum UserSignal {

    READY_CHAT,
    READY_QUERY,
    READY_SINGLE_DAY_ACTION,
    READY_SINGLE_DAY_CONFIRM,
    READY_EXECUTE,
    READY_PLAN,

    /**
     * 对当前待处理任务表示确认。
     */
    CONFIRM,

    /**
     * 明确要求把当前规划同步到日历。
     */
    SYNC_PLAN,

    /**
     * 明确要求为当前规划生成示意图。
     */
    GENERATE_PLAN_IMAGE,

    /**
     * 取消当前待处理任务。
     */
    REJECT,

    /**
     * 修改当前待处理任务。
     */
    MODIFY,

    /**
     * 当前消息与未完成任务无关。状态机不会在本轮执行这项新请求。
     */
    NEW_REQUEST,

    /**
     * 无法判断用户想做什么。
     */
    UNKNOWN
}
