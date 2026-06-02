package com.qiniu.back.module.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiniu.back.module.chat.mcp.McpToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 原生 OpenAI Chat Completions API 客户端，支持 Tool Calling
 */
@Slf4j
@Service
public class OpenAiService {

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;
    private final McpToolRegistry toolRegistry;

    @Value("${app.ai.base-url}")
    private String baseUrl;

    @Value("${app.ai.api-key}")
    private String apiKey;

    @Value("${app.ai.model}")
    private String model;

    public OpenAiService(McpToolRegistry toolRegistry) {
        this.restTemplate = new RestTemplate();
        this.mapper = new ObjectMapper();
        this.toolRegistry = toolRegistry;
    }


    private static final int MAX_TOOL_ROUNDS = 5;

    /**
     * 发送对话请求，自动处理多轮 Tool Calling 循环
     */
    public String chat(String systemPrompt, List<Map<String, String>> messages) {
        List<Map<String, Object>> openAiMessages = buildMessages(systemPrompt, messages);

        // Tool calling loop: 持续调用直到模型返回纯文本
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            Map<String, Object> response = callOpenAi(openAiMessages, true);
            Map<String, Object> choice = getFirstChoice(response);
            Map<String, Object> msg = (Map<String, Object>) choice.get("message");

            if (!msg.containsKey("tool_calls")) {
                return (String) msg.getOrDefault("content", "");
            }

            // 执行 tool calls 并回传结果
            openAiMessages.add(msg);
            List<Map<String, Object>> toolResults = executeToolCalls(msg);
            for (Map<String, Object> tr : toolResults) {
                openAiMessages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", tr.get("id"),
                        "content", tr.get("result")
                ));
            }
        }

        // 超出最大轮次后，强制让模型以文本回复
        Map<String, Object> response = callOpenAi(openAiMessages, false);
        Map<String, Object> choice = getFirstChoice(response);
        Map<String, Object> finalMsg = (Map<String, Object>) choice.get("message");
        return (String) finalMsg.getOrDefault("content", "");
    }

    // ==================== 内部实现 ====================

    private List<Map<String, Object>> buildMessages(String systemPrompt, List<Map<String, String>> history) {
        List<Map<String, Object>> messages = new ArrayList<>();

        messages.add(Map.of("role", "system", "content", systemPrompt));

        for (Map<String, String> m : history) {
            String role = m.get("role");
            String content = m.get("content");
            if (content != null && !content.isEmpty()) {
                messages.add(Map.of("role", role, "content", content));
            }
        }
        return messages;
    }

    private Map<String, Object> callOpenAi(List<Map<String, Object>> messages, boolean withTools) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("temperature", 0.7);
            if (withTools) {
                body.put("tools", getToolDefinitions());
                body.put("tool_choice", "auto");
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            String url = baseUrl + "/v1/chat/completions";
            String requestJson = mapper.writeValueAsString(body);
            log.info("OpenAI 请求: model={}, messages={}条", model, messages.size());

            String responseJson = restTemplate.postForObject(url, new HttpEntity<>(requestJson, headers), String.class);

            return mapper.readValue(responseJson, Map.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 序列化失败", e);
        }
    }

    private Map<String, Object> getFirstChoice(Map<String, Object> response) {
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("AI 返回空响应");
        }
        return choices.get(0);
    }

    /**
     * 执行 tool calls 并将结果回传
     */
    private List<Map<String, Object>> executeToolCalls(Map<String, Object> assistantMsg) {
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) assistantMsg.get("tool_calls");
        List<Map<String, Object>> results = new ArrayList<>();

        for (Map<String, Object> tc : toolCalls) {
            String id = (String) tc.get("id");
            Map<String, Object> function = (Map<String, Object>) tc.get("function");
            String funcName = (String) function.get("name");
            String arguments = (String) function.get("arguments");

            log.info("Tool call: {} args: {}", funcName, arguments);

            String result;
            try {
                result = executeTool(funcName, arguments);
            } catch (Exception e) {
                log.error("Tool 执行失败: {}", funcName, e);
                result = "执行失败: " + e.getMessage();
            }

            results.add(Map.of("id", id, "result", result));
        }
        return results;
    }



    // 原来：整个 executeTool(name, arguments) 里的 switch-case 和 toLong/toInt/toWeekDayList 都删掉
    private String executeTool(String name, String arguments) {
        return toolRegistry.execute(name, arguments);
    }


    /**
     * OpenAI Tool Definitions
     */
    private List<Map<String, Object>> getToolDefinitions() {
        return toolRegistry.toOpenAiTools();
    }
}
