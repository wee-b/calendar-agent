package com.qiniu.back.module.assistant.domain.result;

public record ChatDispatchResult(
        String aiResult,
        boolean needDispatchAgent,
        String dispatchType,
        String currentAgent,
        String nextAgent,
        String flowStage
) {
}
