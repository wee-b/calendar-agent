package com.qiniu.back.module.assistant.controller;

import com.qiniu.back.domain.ResponseDTO;
import com.qiniu.back.module.assistant.domain.dto.ChatRequestDTO;
import com.qiniu.back.module.assistant.domain.vo.ChatResponseVO;
import com.qiniu.back.module.assistant.service.ChatFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;


@Tag(name = "AI 对话模块")
@RestController
@RequestMapping("/chat")
public class ChatController {

    @Autowired
    private ChatFacade chatService;

    @PostMapping
    @Operation(summary = "发送对话消息")
    public ResponseDTO<ChatResponseVO> chat(@RequestBody @Valid ChatRequestDTO request) {
        return ResponseDTO.ok(chatService.chat(request.getSessionId(), request.getMessage()));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式对话（SSE）")
    public SseEmitter streamChat(@RequestBody @Valid ChatRequestDTO request) {
        return chatService.streamChat(request.getSessionId(), request.getMessage());
    }

}
