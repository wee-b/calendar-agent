package com.qiniu.back.module.chat.service;

public record ChatDispatchResult(
        String aiResult,
        boolean needDispatchAgent,
        String dispatchType,
        String currentAgent,
        String nextAgent,
        String flowStage
) {
}
