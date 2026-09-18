package com.qiniu.back.module.assistant.statemachine;

public enum UserSignal {

    /**
     * READY 状态下收到的普通新消息。
     */
    NEW_MESSAGE,

    /**
     * 对当前待处理任务表示确认。
     */
    CONFIRM,

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
