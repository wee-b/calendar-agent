package com.qiniu.back.module.chat.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
public class EmbeddingClient {

    private static final String PROVIDER_OLLAMA = "ollama";
    private static final String PROVIDER_ALIYUN = "aliyun";
    private static final String PROVIDER_OPENAI_COMPATIBLE = "openai-compatible";

    private final EmbeddingProperties props;
    private final RestTemplate rest;

    public EmbeddingClient(EmbeddingProperties props, RestTemplate rest) {
        this.props = props;
        this.rest = rest;
    }

    public List<Float> embed(String text) {
        String provider = normalizeProvider(props.getEmbeddingProvider());
        return switch (provider) {
            case PROVIDER_OLLAMA -> embedByOllama(text);
            case PROVIDER_ALIYUN, PROVIDER_OPENAI_COMPATIBLE -> embedByAliyun(text);
            default -> {
                log.warn("[RAG] 未支持的 embedding provider: {}", props.getEmbeddingProvider());
                yield List.of();
            }
        };
    }

    public String cacheNamespace() {
        return normalizeProvider(props.getEmbeddingProvider()) + ":" + activeModel();
    }

    private List<Float> embedByOllama(String text) {
        String url = trimTrailingSlash(props.getOllama().getUrl()) + "/api/embeddings";
        Map<String, String> body = Map.of("model", props.getOllama().getModel(), "prompt", text);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> req = new HttpEntity<>(body, headers);

            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    url, org.springframework.http.HttpMethod.POST, req,
                    new ParameterizedTypeReference<>() {}
            );

            Map<String, Object> respBody = resp.getBody();
            if (respBody == null) {
                return List.of();
            }
            return toFloatList(respBody.get("embedding"));
        } catch (RestClientException e) {
            log.warn("[RAG] Ollama embedding 请求失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Float> embedByAliyun(String text) {
        if (props.getAliyun().getApiKey() == null || props.getAliyun().getApiKey().isBlank()) {
            log.warn("[RAG] aliyun-api-key 未配置，无法调用 {}", props.getEmbeddingProvider());
            return List.of();
        }

        String url = trimTrailingSlash(props.getAliyun().getBaseUrl()) + "/embeddings";
        Map<String, Object> body = Map.of(
                "model", props.getAliyun().getModel(),
                "input", text
        );

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(props.getAliyun().getApiKey());
            HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);

            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    url, org.springframework.http.HttpMethod.POST, req,
                    new ParameterizedTypeReference<>() {}
            );

            Map<String, Object> respBody = resp.getBody();
            if (respBody == null) {
                return List.of();
            }
            Object data = respBody.get("data");
            if (!(data instanceof List<?> list) || list.isEmpty()) {
                return List.of();
            }
            Object first = list.get(0);
            if (!(first instanceof Map<?, ?> item)) {
                return List.of();
            }
            return toFloatList(item.get("embedding"));
        } catch (RestClientException e) {
            log.warn("[RAG] Aliyun embedding 请求失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Float> toFloatList(Object embedding) {
        if (!(embedding instanceof List<?> list)) {
            return List.of();
        }
        List<Float> floats = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Number number) {
                floats.add(number.floatValue());
            }
        }
        return floats;
    }

    private String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return PROVIDER_OLLAMA;
        }
        return provider.trim().toLowerCase(Locale.ROOT);
    }

    private String activeModel() {
        String provider = normalizeProvider(props.getEmbeddingProvider());
        return PROVIDER_OLLAMA.equals(provider) ? props.getOllama().getModel() : props.getAliyun().getModel();
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
