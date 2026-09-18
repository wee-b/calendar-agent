package com.qiniu.back.module.assistant.service;

import com.qiniu.back.module.assistant.domain.result.ChatDispatchResult;

import java.util.function.Consumer;

public interface ChatService {

    ChatDispatchResult process(Long userId, String sessionId, String message, Long userDialogueId);

    ChatDispatchResult process(Long userId, String sessionId, String message, Long userDialogueId,
                               Consumer<String> progress);
}
