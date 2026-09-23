package com.qiniu.back.module.assistant.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiniu.back.domain.ErrorCode;
import com.qiniu.back.exception.BusinessException;
import com.qiniu.back.module.assistant.config.AgentModelBeans;
import com.qiniu.back.module.assistant.config.AiProperties;
import com.qiniu.back.module.assistant.domain.model.PlanDraft;
import com.qiniu.back.util.PromptLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

/** Generates a visual summary for a plan only after the user explicitly requests one. */
@Slf4j
@Component
public class ImageAgent {

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient.Builder restClientBuilder;

    public ImageAgent(AiProperties properties,
                      ObjectMapper objectMapper,
                      RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClientBuilder = restClientBuilder;
    }

    public String generate(PlanDraft draft) {
        ResolvedImageModel resolved = resolve();
        ImageGenerationRequest request = new ImageGenerationRequest(
                resolved.model(), buildPrompt(draft), resolved.size(),
                "jpeg", "url", false, resolved.watermark());
        try {
            ImageGenerationResponse response = restClientBuilder
                    .baseUrl(stripTrailingSlash(resolved.baseUrl()))
                    .defaultHeader("Authorization", "Bearer " + resolved.apiKey())
                    .build()
                    .post()
                    .uri("/images/generations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(ImageGenerationResponse.class);
            String url = firstImageUrl(response);
            return "已生成规划示意图：\n\n![规划示意图](" + url + ")";
        } catch (RestClientResponseException exception) {
            throw mapApiError(exception);
        }
    }

    private ResolvedImageModel resolve() {
        AiProperties.AgentModel agent = properties.getAgents().get(AgentModelBeans.IMAGE_AGENT);
        if (agent == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "缺少 app.ai.agents.image 配置");
        }
        String providerName = isBlank(agent.getProvider())
                ? properties.getDefaultProvider() : agent.getProvider();
        AiProperties.Provider provider = properties.getProviders().get(providerName);
        if (provider == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "ImageAgent 引用的 provider 不存在：" + providerName);
        }
        if (!"image-generation".equals(provider.getType())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "ImageAgent provider 类型必须是 image-generation");
        }
        if (isBlank(provider.getApiKey()) || isBlank(provider.getBaseUrl())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "ImageAgent provider 的 api-key 和 base-url 必须配置");
        }
        if (isBlank(agent.getModel())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "app.ai.agents.image.model 必须配置");
        }
        return new ResolvedImageModel(provider.getApiKey(), provider.getBaseUrl(), agent.getModel(),
                isBlank(agent.getSize()) ? "2K" : agent.getSize(),
                agent.getWatermark() == null || agent.getWatermark());
    }

    private String buildPrompt(PlanDraft draft) {
        return PromptLoader.load("image-system.txt") + "\n\n规划数据：\n" + draft.getPlanJson();
    }

    private String firstImageUrl(ImageGenerationResponse response) {
        if (response == null || response.data() == null || response.data().isEmpty()
                || isBlank(response.data().get(0).url())) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "图片生成接口未返回图片地址");
        }
        return response.data().get(0).url();
    }

    private BusinessException mapApiError(RestClientResponseException exception) {
        String code = null;
        String message = null;
        try {
            JsonNode error = objectMapper.readTree(exception.getResponseBodyAsString()).path("error");
            code = error.path("code").asText(null);
            message = error.path("message").asText(null);
        } catch (Exception parseException) {
            log.warn("ImageAgent error response parse failed: {}", parseException.getMessage());
        }
        if ("SetLimitExceeded".equals(code)) {
            return new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    "图片模型已达到账号用量限制，请调整火山方舟模型限额后重试");
        }
        log.warn("ImageAgent request failed: status={}, code={}, message={}",
                exception.getStatusCode().value(), code, message);
        return new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR,
                isBlank(message) ? "图片生成失败，请稍后重试" : message);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private record ResolvedImageModel(
            String apiKey, String baseUrl, String model, String size, boolean watermark) {
    }

    private record ImageGenerationRequest(
            String model,
            String prompt,
            String size,
            @JsonProperty("output_format") String outputFormat,
            @JsonProperty("response_format") String responseFormat,
            boolean stream,
            boolean watermark) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ImageGenerationResponse(List<ImageData> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ImageData(String url, String size) {
    }
}
