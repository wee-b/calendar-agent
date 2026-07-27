package com.qiniu.back.module.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiniu.back.domain.chat.AiDialogue;
import com.qiniu.back.domain.chat.PlanDraft;
import com.qiniu.back.domain.chat.vo.ChatHistoryItemVO;
import com.qiniu.back.domain.chat.vo.ChatResponseVO;
import com.qiniu.back.domain.chat.vo.ChatSessionVO;
import com.qiniu.back.module.chat.agent.AgentOrchestrator;
import com.qiniu.back.module.chat.mapper.AiDialogueMapper;
import com.qiniu.back.module.chat.service.ChatService;
import com.qiniu.back.module.chat.service.PlanDraftService;
import com.qiniu.back.util.ChatSessionContext;
import com.qiniu.back.util.LoginUserContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    private static final int MAX_HISTORY = 20;

    @Autowired
    private AgentOrchestrator agentOrchestrator;

    @Autowired
    private AiDialogueMapper aiDialogueMapper;

    @Autowired
    private PlanDraftService planDraftService;

    @Override
    public ChatResponseVO chat(String sessionId, String message) {
        Long userId = LoginUserContext.getUserId();
        String sid = normalizeSessionId(sessionId);

        saveDialogue(userId, sid, "user", message, null);

        Optional<PlanDraft> pendingDraft = planDraftService.findLatestPending(userId, sid);
        if (pendingDraft.isPresent() && planDraftService.isConfirmMessage(message)) {
            String aiResult = planDraftService.buildSyncReply(planDraftService.syncDraft(pendingDraft.get()));
            saveDialogue(userId, sid, "assistant", null, aiResult);
            return response(sid, aiResult);
        }

        List<ChatMessage> history = buildMessageList(loadHistory(userId, sid));
        String aiResult;
        try {
            ChatSessionContext.setSessionId(sid);
            aiResult = agentOrchestrator.orchestrate(message, history);
        } catch (Exception e) {
            log.error("Agent orchestration failed", e);
            aiResult = "\u62b1\u6b49\uff0c\u6211\u6682\u65f6\u65e0\u6cd5\u5904\u7406\u8fd9\u4e2a\u8bf7\u6c42\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5\u3002";
        } finally {
            ChatSessionContext.remove();
        }

        saveDialogue(userId, sid, "assistant", null, aiResult);
        return response(sid, aiResult);
    }

    @Override
    public SseEmitter streamChat(String sessionId, String message) {
        Long userId = LoginUserContext.getUserId();
        String sid = normalizeSessionId(sessionId);

        saveDialogue(userId, sid, "user", message, null);

        Optional<PlanDraft> pendingDraft = planDraftService.findLatestPending(userId, sid);
        if (pendingDraft.isPresent() && planDraftService.isConfirmMessage(message)) {
            SseEmitter emitter = new SseEmitter(300_000L);
            CompletableFuture.runAsync(() -> {
                LoginUserContext.setUserId(userId);
                try {
                    String aiResult = planDraftService.buildSyncReply(planDraftService.syncDraft(pendingDraft.get()));
                    saveDialogue(userId, sid, "assistant", null, aiResult);
                    emitter.send(SseEmitter.event().data(aiResult));
                    emitter.complete();
                } catch (Exception e) {
                    log.error("Plan draft sync failed", e);
                    emitter.completeWithError(e);
                } finally {
                    LoginUserContext.remove();
                }
            });
            return emitter;
        }

        List<ChatMessage> history = buildMessageList(loadHistory(userId, sid));
        SseEmitter emitter = new SseEmitter(300_000L);

        CompletableFuture.runAsync(() -> {
            LoginUserContext.setUserId(userId);
            ChatSessionContext.setSessionId(sid);
            try {
                agentOrchestrator.orchestrateStreamInternal(emitter, message, history,
                        fullResponse -> saveDialogue(userId, sid, "assistant", null, fullResponse));
            } catch (Exception e) {
                log.error("Streaming agent orchestration failed", e);
                emitter.completeWithError(e);
            } finally {
                ChatSessionContext.remove();
                LoginUserContext.remove();
            }
        });

        emitter.onTimeout(() -> log.warn("SSE connection timeout"));
        emitter.onError(e -> log.error("SSE connection error", e));
        return emitter;
    }

    @Override
    public String newSession() {
        return UUID.randomUUID().toString();
    }

    @Override
    public List<ChatHistoryItemVO> getHistory(String sessionId) {
        Long userId = LoginUserContext.getUserId();
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
                        d.getCreateTime()))
                .toList();
    }

    @Override
    public void deleteSession(String sessionId) {
        Long userId = LoginUserContext.getUserId();
        aiDialogueMapper.delete(new LambdaQueryWrapper<AiDialogue>()
                .eq(AiDialogue::getUserId, userId)
                .eq(AiDialogue::getSessionId, sessionId));
    }

    @Override
    public void deleteLastRound(String sessionId) {
        Long userId = LoginUserContext.getUserId();
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

    @Override
    public List<ChatSessionVO> listSessions() {
        Long userId = LoginUserContext.getUserId();
        List<AiDialogue> all = aiDialogueMapper.selectList(
                new LambdaQueryWrapper<AiDialogue>()
                        .eq(AiDialogue::getUserId, userId)
                        .orderByAsc(AiDialogue::getCreateTime));

        Map<String, List<AiDialogue>> grouped = all.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        AiDialogue::getSessionId, LinkedHashMap::new, java.util.stream.Collectors.toList()));

        List<ChatSessionVO> result = new ArrayList<>();
        for (Map.Entry<String, List<AiDialogue>> entry : grouped.entrySet()) {
            String sid = entry.getKey();
            List<AiDialogue> msgs = entry.getValue();
            String title = msgs.stream()
                    .filter(d -> "user".equals(d.getRole()) && d.getUserText() != null)
                    .findFirst()
                    .map(d -> d.getUserText().length() > 30 ? d.getUserText().substring(0, 30) + "..." : d.getUserText())
                    .orElse("\u65b0\u5bf9\u8bdd");
            result.add(new ChatSessionVO(sid, title, msgs.get(0).getCreateTime(), msgs.size()));
        }
        return result;
    }

    @Override
    public List<ChatHistoryItemVO> getLatestSession() {
        Long userId = LoginUserContext.getUserId();
        AiDialogue latest = aiDialogueMapper.selectOne(
                new LambdaQueryWrapper<AiDialogue>()
                        .eq(AiDialogue::getUserId, userId)
                        .orderByDesc(AiDialogue::getCreateTime)
                        .last("LIMIT 1"));

        if (latest == null) return Collections.emptyList();

        return getHistory(latest.getSessionId());
    }

    private String normalizeSessionId(String sessionId) {
        return (sessionId == null || sessionId.isEmpty()) ? UUID.randomUUID().toString() : sessionId;
    }

    private ChatResponseVO response(String sessionId, String aiResult) {
        ChatResponseVO vo = new ChatResponseVO();
        vo.setSessionId(sessionId);
        vo.setAiResult(aiResult);
        return vo;
    }

    private List<AiDialogue> loadHistory(Long userId, String sessionId) {
        List<AiDialogue> list = aiDialogueMapper.selectList(
                new LambdaQueryWrapper<AiDialogue>()
                        .eq(AiDialogue::getUserId, userId)
                        .eq(AiDialogue::getSessionId, sessionId)
                        .orderByDesc(AiDialogue::getCreateTime)
                        .last("LIMIT " + (MAX_HISTORY * 2)));
        Collections.reverse(list);
        return list;
    }

    private List<ChatMessage> buildMessageList(List<AiDialogue> history) {
        List<ChatMessage> messages = new ArrayList<>();
        for (AiDialogue d : history) {
            if (d.getUserText() != null && !d.getUserText().isEmpty()) {
                messages.add(new UserMessage(d.getUserText()));
            }
            if (d.getAiResult() != null && !d.getAiResult().isEmpty()) {
                messages.add(new AiMessage(d.getAiResult()));
            }
        }
        return messages;
    }

    private void saveDialogue(Long userId, String sessionId, String role, String userText, String aiResult) {
        AiDialogue d = new AiDialogue();
        d.setUserId(userId);
        d.setSessionId(sessionId);
        d.setRole(role);
        d.setUserText(userText);
        d.setAiResult(aiResult);
        aiDialogueMapper.insert(d);
    }
}
