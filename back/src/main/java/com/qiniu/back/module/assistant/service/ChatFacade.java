package com.qiniu.back.module.assistant.service;

import com.qiniu.back.module.assistant.domain.vo.ChatResponseVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface ChatFacade {

    ChatResponseVO chat(String sessionId, String message);

    SseEmitter streamChat(String sessionId, String message);

}
