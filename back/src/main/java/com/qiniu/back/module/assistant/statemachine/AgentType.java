package com.qiniu.back.module.assistant.statemachine;

/** 转换表只有四个 Agent 目标，具体业务动作留在各自的处理器中。 */
public enum AgentType { CHAT, PLANNER, EXECUTOR, IMAGE }
