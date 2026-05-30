package com.qiniu.back;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试大模型接入，直接运行 main 方法
 */
public class AiConnectionTest {

    // ========== 改这里 ==========
    private static final String API_KEY = "sk-1efd200fba634a289cbc152751c1db75";
    private static final String BASE_URL = "https://api.deepseek.com";
    private static final String MODEL = "deepseek-v4-flash";
    // ============================

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        Map<String, Object> body = new HashMap<>();
        body.put("model", MODEL);
        body.put("messages", List.of(
                Map.of("role", "user", "content", "请用一句话介绍你自己")
        ));
        body.put("max_tokens", 100);
        body.put("temperature", 0.7);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(30))
                .build();

        System.out.println("正在测试大模型接入...");
        System.out.println("  URL: " + BASE_URL);
        System.out.println("  Model: " + MODEL);
        System.out.println();

        long start = System.currentTimeMillis();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        long cost = System.currentTimeMillis() - start;

        Map<String, Object> result = mapper.readValue(response.body(), Map.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
        Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
        String content = (String) msg.get("content");

        System.out.println("============================================");
        System.out.println("  接入成功！");
        System.out.println("  模型: " + MODEL);
        System.out.println("  回复: " + content);
        System.out.println("  耗时: " + cost + "ms");
        System.out.println("============================================");
    }
}
