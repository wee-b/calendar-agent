package com.qiniu.back.module.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiniu.back.domain.chat.AiDialogue;
import com.qiniu.back.domain.chat.vo.ChatHistoryItemVO;
import com.qiniu.back.domain.chat.vo.ChatSessionVO;
import com.qiniu.back.module.chat.mapper.AiDialogueMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public List<ChatHistoryItemVO> getHistory(Long userId, String sessionId) {
        List<AiDialogue> list = aiDialogueMapper.selectList(
                new LambdaQueryWrapper<AiDialogue>()
                        .eq(AiDialogue::getUserId, userId)
                        .eq(AiDialogue::getSessionId, sessionId)
                        .orderByAsc(AiDialogue::getCreateTime));

        return list.stream()
                .map(d -> new ChatHistoryItemVO(
                        d.getDialogueId(),
                        d.getRole(),
                        d.getRole().equals("user") ? d.getUserText() : d.getAiResult(),
                        d.getCreateTime(),
                        d.getResponseTimeMs()))
                .toList();
    }

    public void deleteSession(Long userId, String sessionId) {
        aiDialogueMapper.delete(new LambdaQueryWrapper<AiDialogue>()
                .eq(AiDialogue::getUserId, userId)
                .eq(AiDialogue::getSessionId, sessionId));
    }

    public void deleteLastRound(Long userId, String sessionId) {
        List<AiDialogue> last = aiDialogueMapper.selectList(
                new LambdaQueryWrapper<AiDialogue>()
                        .eq(AiDialogue::getUserId, userId)
                        .eq(AiDialogue::getSessionId, sessionId)
                        .orderByDesc(AiDialogue::getDialogueId)
                        .last("LIMIT 2"));

        if (last.size() < 2) return;

        aiDialogueMapper.deleteBatchIds(
                last.stream().map(AiDialogue::getDialogueId).toList());
    }

    public List<ChatSessionVO> listSessions(Long userId) {
        List<AiDialogue> all = aiDialogueMapper.selectList(
                new LambdaQueryWrapper<AiDialogue>()
                        .eq(AiDialogue::getUserId, userId)
                        .orderByDesc(AiDialogue::getCreateTime));

        Map<String, List<AiDialogue>> grouped = all.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        AiDialogue::getSessionId, LinkedHashMap::new, java.util.stream.Collectors.toList()));

        List<ChatSessionVO> result = new ArrayList<>();
        for (Map.Entry<String, List<AiDialogue>> entry : grouped.entrySet()) {
            String sid = entry.getKey();
            List<AiDialogue> msgs = entry.getValue();
            String title = "新对话";
            for (int i = msgs.size() - 1; i >= 0; i--) {
                AiDialogue dialogue = msgs.get(i);
                if ("user".equals(dialogue.getRole()) && dialogue.getUserText() != null) {
                    String userText = dialogue.getUserText();
                    title = userText.length() > 30 ? userText.substring(0, 30) + "..." : userText;
                    break;
                }
            }
            result.add(new ChatSessionVO(sid, title, msgs.get(0).getCreateTime(), msgs.size()));
        }
        return result;
    }

    public List<ChatHistoryItemVO> getLatestSession(Long userId) {
        AiDialogue latest = aiDialogueMapper.selectOne(
                new LambdaQueryWrapper<AiDialogue>()
                        .eq(AiDialogue::getUserId, userId)
                        .orderByDesc(AiDialogue::getCreateTime)
                        .last("LIMIT 1"));

        if (latest == null) return Collections.emptyList();
        return getHistory(userId, latest.getSessionId());
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
