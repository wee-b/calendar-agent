package com.qiniu.back.module.chat.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * MCP (Model Context Protocol) JSON-RPC 端点。
 * 对接 Claude Desktop、LangGraph 等 MCP 客户端。
 */
@Slf4j
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
public class McpController {

    private final McpToolRegistry registry;

    /**
     * MCP JSON-RPC 统一入口。
     * 请求格式：{"jsonrpc":"2.0","method":"tools/list"|"tools/call","params":{...},"id":1}
     */
    @PostMapping
    public Map<String, Object> handleJsonRpc(@RequestBody Map<String, Object> request) {
        String method = (String) request.get("method");
        Object id = request.getOrDefault("id", 0);

        log.info("MCP 请求: method={}, id={}", method, id);

        try {
            Object result = switch (method) {
                case "tools/list" -> handleListTools();
                case "tools/call" -> handleCallTool(request);
                default -> throw new IllegalArgumentException("不支持的方法: " + method);
            };

            return Map.of("jsonrpc", "2.0", "result", result, "id", id);
        } catch (Exception e) {
            log.error("MCP 处理失败: {}", e.getMessage());
            return Map.of(
                    "jsonrpc", "2.0",
                    "error", Map.of("code", -1, "message", e.getMessage()),
                    "id", id
            );
        }
    }

    private Map<String, Object> handleListTools() {
        return Map.of("tools", registry.toMcpToolList());
    }

    private Map<String, Object> handleCallTool(Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) request.get("params");
        String name = (String) params.get("name");
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");

        // 将 arguments Map 序列化为 JSON 字符串统一处理
        String argumentsJson;
        try {
            argumentsJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(arguments);
        } catch (Exception e) {
            throw new RuntimeException("参数序列化失败", e);
        }

        String text = registry.execute(name, argumentsJson);

        return Map.of("content", java.util.List.of(
                Map.of("type", "text", "text", text)
        ));
    }
}
