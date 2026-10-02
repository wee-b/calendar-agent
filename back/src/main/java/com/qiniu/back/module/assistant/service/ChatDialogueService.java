package com.qiniu.back.module.assistant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiniu.back.module.assistant.domain.model.AiDialogue;
import com.qiniu.back.module.assistant.mapper.AiDialogueMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;


@Slf4j
@Service
public class ChatDialogueService {

    @Autowired
    private AiDialogueMapper aiDialogueMapper;

    @Autowired
    private ChatContextSummaryService chatContextSummaryService;

    public Long saveUserDialogue(Long userId, String sessionId, String message) {
        return saveDialogue(userId, sessionId, "user", message, null, null);
    }

    public Long saveAssistantDialogue(Long userId, String sessionId, String aiResult, Long responseTimeMs) {
        Long dialogueId = saveDialogue(userId, sessionId, "assistant", null, aiResult, responseTimeMs);
        chatContextSummaryService.refreshSummaryAsync(userId, sessionId);
        return dialogueId;
    }

    public String findLatestAssistantReply(Long userId, String sessionId, Long beforeDialogueId) {
        LambdaQueryWrapper<AiDialogue> wrapper = new LambdaQueryWrapper<AiDialogue>()
                .eq(AiDialogue::getUserId, userId)
                .eq(AiDialogue::getSessionId, sessionId)
                .eq(AiDialogue::getRole, "assistant")
                .isNotNull(AiDialogue::getAiResult)
                .ne(AiDialogue::getAiResult, "");
        if (beforeDialogueId != null) {
            wrapper.lt(AiDialogue::getDialogueId, beforeDialogueId);
        }
        AiDialogue last = aiDialogueMapper.selectOne(wrapper
                .orderByDesc(AiDialogue::getDialogueId)
                .last("LIMIT 1"));
        if (last == null || last.getAiResult() == null || last.getAiResult().isBlank()) {
            return null;
        }
        return last.getAiResult();
    }

    private Long saveDialogue(Long userId, String sessionId, String role, String userText,
                              String aiResult, Long responseTimeMs) {
        AiDialogue d = new AiDialogue();
        d.setUserId(userId);
        d.setSessionId(sessionId);
        d.setRole(role);
        d.setUserText(userText);
        d.setAiResult(aiResult);
        d.setResponseTimeMs(responseTimeMs);
        aiDialogueMapper.insert(d);
        return d.getDialogueId();
    }
}
