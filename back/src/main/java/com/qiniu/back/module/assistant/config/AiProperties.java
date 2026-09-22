package com.qiniu.back.module.assistant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    /** 某个 agent 未指定 provider 时使用的厂家。 */
    private String defaultProvider = "dashscope";

    private Map<String, Provider> providers = new LinkedHashMap<>();

    private Map<String, AgentModel> agents = new LinkedHashMap<>();

    @Data
    public static class Provider {
        /** openai-compatible 可对接百炼、OpenAI、DeepSeek 官方等；后续可加 ollama 等类型。 */
        private String type = "openai-compatible";
        private String apiKey;
        private String baseUrl;
    }

    @Data
    public static class AgentModel {
        private String provider;
        private String model;
        private Double temperature;
    }
}
