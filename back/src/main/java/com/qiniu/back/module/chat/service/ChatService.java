package com.qiniu.back.module.chat.service;

import com.qiniu.back.domain.chat.vo.ChatHistoryItemVO;
import com.qiniu.back.domain.chat.vo.ChatResponseVO;
import com.qiniu.back.domain.chat.vo.ChatSessionVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

public interface ChatService {

    ChatResponseVO chat(String sessionId, String message);

    SseEmitter streamChat(String sessionId, String message);

    String newSession();

    List<ChatHistoryItemVO> getHistory(String sessionId);

    void deleteSession(String sessionId);

    void deleteLastRound(String sessionId);

    List<ChatSessionVO> listSessions();

    List<ChatHistoryItemVO> getLatestSession();
}
