package com.qiniu.back.module.chat.service.impl;

import com.qiniu.back.domain.chat.vo.ChatHistoryItemVO;
import com.qiniu.back.domain.chat.vo.ChatResponseVO;
import com.qiniu.back.domain.chat.vo.ChatSessionVO;
import com.qiniu.back.module.chat.service.ChatDialogueService;
import com.qiniu.back.module.chat.service.ChatDispatchResult;
import com.qiniu.back.module.chat.service.ChatRequestProcessor;
import com.qiniu.back.module.chat.service.ChatService;
import com.qiniu.back.module.memory.service.UserMemoryService;
import com.qiniu.back.util.ChatSessionContext;
import com.qiniu.back.util.LoginUserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    @Autowired
    private ChatDialogueService chatDialogueService;

    @Autowired
    private ChatRequestProcessor chatRequestProcessor;

    @Autowired
    private UserMemoryService userMemoryService;

    @Autowired
    @Qualifier("chatSseExecutor")
    private Executor chatSseExecutor;

    @Override
    public ChatResponseVO chat(String sessionId, String message) {
        long responseStartTime = System.currentTimeMillis();
        Long userId = LoginUserContext.getUserId();
        String sid = normalizeSessionId(sessionId);

        Long userDialogueId = chatDialogueService.saveUserDialogue(userId, sid, message);
        userMemoryService.extractAndSaveFromUserMessageAsync(userId, userDialogueId, message);

        ChatDispatchResult result = chatRequestProcessor.process(userId, sid, message, userDialogueId);
        Long responseTimeMs = elapsedSince(responseStartTime);
        chatDialogueService.saveAssistantDialogue(userId, sid, result.aiResult(), responseTimeMs);

        return response(sid, result, responseTimeMs);
    }

    @Override
    public SseEmitter streamChat(String sessionId, String message) {
        long responseStartTime = System.currentTimeMillis();
        Long userId = LoginUserContext.getUserId();
        String sid = normalizeSessionId(sessionId);
        SseEmitter emitter = new SseEmitter(300_000L);

        try {
            CompletableFuture.runAsync(() -> handleStreamChatTask(emitter, userId, sid, message, responseStartTime),
                    chatSseExecutor);
        } catch (RejectedExecutionException e) {
            log.warn("Chat SSE executor is saturated, reject sessionId={}", sid, e);
            sendBusyAndComplete(emitter);
        }

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
        return chatDialogueService.getHistory(LoginUserContext.getUserId(), sessionId);
    }

    @Override
    public void deleteSession(String sessionId) {
        chatDialogueService.deleteSession(LoginUserContext.getUserId(), sessionId);
    }

    @Override
    public void deleteLastRound(String sessionId) {
        chatDialogueService.deleteLastRound(LoginUserContext.getUserId(), sessionId);
    }

    @Override
    public List<ChatSessionVO> listSessions() {
        return chatDialogueService.listSessions(LoginUserContext.getUserId());
    }

    @Override
    public List<ChatHistoryItemVO> getLatestSession() {
        return chatDialogueService.getLatestSession(LoginUserContext.getUserId());
    }

    private void handleStreamChatTask(SseEmitter emitter, Long userId, String sid,
                                      String message, long responseStartTime) {
        LoginUserContext.setUserId(userId);
        ChatSessionContext.setSessionId(sid);
        try {
            Long userDialogueId = report(emitter, "保存用户消息",
                    () -> chatDialogueService.saveUserDialogue(userId, sid, message));
            report(emitter, "提取长期记忆",
                    () -> userMemoryService.extractAndSaveFromUserMessageAsync(userId, userDialogueId, message));

            ChatDispatchResult result = chatRequestProcessor.process(
                    userId, sid, message, userDialogueId, progress -> sendProgress(emitter, progress));
            sendFinalResult(emitter, userId, sid, result.aiResult(), responseStartTime);
        } catch (Exception e) {
            log.error("Streaming agent orchestration failed", e);
            emitter.completeWithError(e);
        } finally {
            ChatSessionContext.remove();
            LoginUserContext.remove();
        }
    }

    private void sendFinalResult(SseEmitter emitter, Long userId, String sessionId,
                                 String aiResult, long responseStartTime) throws Exception {
        sendProgress(emitter, "开始：保存助手回复");
        Long responseTimeMs = elapsedSince(responseStartTime);
        chatDialogueService.saveAssistantDialogue(userId, sessionId, aiResult, responseTimeMs);
        sendProgress(emitter, "完成：保存助手回复");
        emitter.send(SseEmitter.event().data(aiResult));
        emitter.send(SseEmitter.event().name("responseTime").data(responseTimeMs));
        emitter.complete();
    }

    private <T> T report(SseEmitter emitter, String step, Supplier<T> action) {
        sendProgress(emitter, "开始：" + step);
        try {
            T result = action.get();
            sendProgress(emitter, "完成：" + step);
            return result;
        } catch (RuntimeException e) {
            sendProgress(emitter, "失败：" + step);
            throw e;
        }
    }

    private void report(SseEmitter emitter, String step, Runnable action) {
        report(emitter, step, () -> {
            action.run();
            return null;
        });
    }

    private void sendBusyAndComplete(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("progress").data("失败：聊天服务繁忙"));
            emitter.send(SseEmitter.event().data("当前聊天请求较多，请稍后再试。"));
        } catch (Exception sendError) {
            log.warn("SSE busy response send failed", sendError);
        } finally {
            emitter.complete();
        }
    }

    private void sendProgress(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("progress").data(message));
        } catch (Exception e) {
            log.warn("SSE progress send failed: {}", message, e);
        }
    }

    private ChatResponseVO response(String sessionId, ChatDispatchResult result, Long responseTimeMs) {
        ChatResponseVO vo = new ChatResponseVO();
        vo.setSessionId(sessionId);
        vo.setAiResult(result.aiResult());
        vo.setResponseTimeMs(responseTimeMs);
        vo.setNeedDispatchAgent(result.needDispatchAgent());
        vo.setDispatchType(result.dispatchType());
        vo.setCurrentAgent(result.currentAgent());
        vo.setNextAgent(result.nextAgent());
        vo.setFlowStage(result.flowStage());
        return vo;
    }

    private String normalizeSessionId(String sessionId) {
        return (sessionId == null || sessionId.isEmpty()) ? UUID.randomUUID().toString() : sessionId;
    }

    private Long elapsedSince(long startTimeMs) {
        return Math.max(1, System.currentTimeMillis() - startTimeMs);
    }
}
