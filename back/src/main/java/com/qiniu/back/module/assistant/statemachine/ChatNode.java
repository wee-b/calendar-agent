package com.qiniu.back.module.assistant.statemachine;

public enum ChatNode {

    /** 直接返回 RouteAgent 生成的普通回复。 */
    RESPOND_DIRECTLY,

    /**
     * 直接调用 ChatAgent 查询工具。
     */
    QUERY_CALENDAR,

    /**
     * 直接调用 ChatAgent 单日写入工具。
     */
    EXECUTE_SINGLE_DAY_ACTION,

    /** 写入单日操作确认状态。 */
    PREPARE_SINGLE_DAY_CONFIRMATION,

    /** 写入通用执行确认状态。 */
    PREPARE_EXECUTION_CONFIRMATION,

    /** 写入规划确认状态。 */
    PREPARE_PLAN_CONFIRMATION,

    /**
     * 执行已经确认的普通写操作。
     */
    EXECUTE_PENDING_ACTION,

    /**
     * 根据已确认的需求生成规划草稿。
     */
    GENERATE_PLAN,

    /**
     * 将确认后的规划草稿同步到日历。
     */
    APPLY_PLAN,

    /**
     * 修改待执行动作。
     */
    MODIFY_PENDING_ACTION,

    /**
     * 根据用户意见重新生成规划。
     */
    REVISE_PLAN,

    /**
     * 取消当前待处理流程。
     */
    CANCEL_PENDING_ACTION,

    /**
     * 当前消息与等待中的流程不匹配。
     */
    EXPLAIN_PENDING_STATE
}
