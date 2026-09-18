package com.qiniu.back.module.assistant.service;

import com.qiniu.back.module.assistant.domain.vo.ChatHistoryItemVO;
import com.qiniu.back.module.assistant.domain.vo.ChatResponseVO;
import com.qiniu.back.module.assistant.domain.vo.ChatSessionVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

public interface ChatFacade {

    ChatResponseVO chat(String sessionId, String message);

    SseEmitter streamChat(String sessionId, String message);

    String newSession();

    List<ChatHistoryItemVO> getHistory(String sessionId);

    void deleteSession(String sessionId);

    void deleteLastRound(String sessionId);

    List<ChatSessionVO> listSessions();

    List<ChatHistoryItemVO> getLatestSession();
}
