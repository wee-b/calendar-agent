package com.qiniu.back.module.chat.controller;

import com.qiniu.back.domain.ResponseDTO;
import com.qiniu.back.domain.chat.dto.ChatRequestDTO;
import com.qiniu.back.domain.chat.vo.ChatHistoryItemVO;
import com.qiniu.back.domain.chat.vo.ChatResponseVO;
import com.qiniu.back.domain.chat.vo.ChatSessionVO;
import com.qiniu.back.module.chat.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@Tag(name = "AI 对话模块")
@RestController
@RequestMapping("/chat")
public class ChatController {

    @Autowired
    private ChatService chatService;

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

    @PostMapping("/new-session")
    @Operation(summary = "开启新对话")
    public ResponseDTO<Map<String, String>> newSession() {
        return ResponseDTO.ok(Map.of("sessionId", chatService.newSession()));
    }

    @GetMapping("/history")
    @Operation(summary = "获取当前对话历史记录")
    public ResponseDTO<List<ChatHistoryItemVO>> history(@RequestParam String sessionId) {
        return ResponseDTO.ok(chatService.getHistory(sessionId));
    }

    @DeleteMapping("/session")
    @Operation(summary = "删除整个对话")
    public ResponseDTO<Void> deleteSession(@RequestParam String sessionId) {
        chatService.deleteSession(sessionId);
        return ResponseDTO.ok();
    }

    @DeleteMapping("/last-round")
    @Operation(summary = "撤回上一轮对话（删除用户+AI各一条）")
    public ResponseDTO<Void> deleteLastRound(@RequestParam String sessionId) {
        chatService.deleteLastRound(sessionId);
        return ResponseDTO.ok();
    }

    @GetMapping("/sessions")
    @Operation(summary = "获取当前用户历史对话列表")
    public ResponseDTO<List<ChatSessionVO>> sessions() {
        return ResponseDTO.ok(chatService.listSessions());
    }

    @GetMapping("/latest")
    @Operation(summary = "获取用户最新对话历史记录")
    public ResponseDTO<List<ChatHistoryItemVO>> latest() {
        return ResponseDTO.ok(chatService.getLatestSession());
    }
}
