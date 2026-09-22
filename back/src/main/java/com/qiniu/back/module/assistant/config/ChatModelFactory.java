package com.qiniu.back.module.assistant.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChatModelFactory {

    private final AiProperties properties;
    private final Map<String, ChatModel> chatModels = new ConcurrentHashMap<>();
    private final Map<String, OpenAiStreamingChatModel> streamingModels = new ConcurrentHashMap<>();

    public ChatModel createForAgent(String agent) {
        return chatModels.computeIfAbsent(cacheKey(agent, false), ignored -> buildChatModel(agent));
    }

    public OpenAiStreamingChatModel createStreamingForAgent(String agent) {
        return streamingModels.computeIfAbsent(cacheKey(agent, true), ignored -> buildStreamingModel(agent));
    }

    private ChatModel buildChatModel(String agent) {
        ResolvedModel resolved = resolve(agent);
        ChatModel model = switch (resolved.type()) {
            case "openai-compatible", "openai", "dashscope" -> OpenAiChatModel.builder()
                    .apiKey(resolved.apiKey())
                    .baseUrl(resolved.baseUrl())
                    .modelName(resolved.model())
                    .temperature(resolved.temperature())
                    .build();
            default -> throw unsupported(resolved.type());
        };
        log.info("[AI] agent={} provider={} type={} model={} baseUrl={}",
                agent, resolved.providerName(), resolved.type(), resolved.model(), resolved.baseUrl());
        return model;
    }

    private OpenAiStreamingChatModel buildStreamingModel(String agent) {
        ResolvedModel resolved = resolve(agent);
        return switch (resolved.type()) {
            case "openai-compatible", "openai", "dashscope" -> OpenAiStreamingChatModel.builder()
                    .apiKey(resolved.apiKey())
                    .baseUrl(resolved.baseUrl())
                    .modelName(resolved.model())
                    .temperature(resolved.temperature())
                    .build();
            default -> throw unsupported(resolved.type());
        };
    }

    ResolvedModel resolve(String agent) {
        if (properties.getAgents() == null || !properties.getAgents().containsKey(agent)) {
            throw new IllegalStateException("Missing app.ai.agents." + agent
                    + ". Available: " + (properties.getAgents() == null ? "[]" : properties.getAgents().keySet()));
        }
        AiProperties.AgentModel spec = properties.getAgents().get(agent);
        if (spec.getModel() == null || spec.getModel().isBlank()) {
            throw new IllegalStateException("app.ai.agents." + agent + ".model is required");
        }
        String providerName = spec.getProvider() == null || spec.getProvider().isBlank()
                ? properties.getDefaultProvider()
                : spec.getProvider();
        AiProperties.Provider provider = providerOf(providerName);
        String type = provider.getType() == null || provider.getType().isBlank()
                ? "openai-compatible"
                : provider.getType().trim().toLowerCase(Locale.ROOT);
        if (isBlank(provider.getApiKey()) || isBlank(provider.getBaseUrl())) {
            throw new IllegalStateException("AI provider '" + providerName
                    + "' must have its own api-key and base-url under app.ai.providers." + providerName);
        }
        double temperature = spec.getTemperature() == null ? 0.7 : spec.getTemperature();
        return new ResolvedModel(providerName, type, provider.getApiKey(), provider.getBaseUrl(),
                spec.getModel(), temperature);
    }

    private AiProperties.Provider providerOf(String providerName) {
        if (properties.getProviders() == null || !properties.getProviders().containsKey(providerName)) {
            throw new IllegalStateException("Unknown AI provider '" + providerName
                    + "'. Add it under app.ai.providers with its own api-key and base-url");
        }
        return properties.getProviders().get(providerName);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String cacheKey(String agent, boolean streaming) {
        ResolvedModel resolved = resolve(agent);
        return resolved.providerName() + "|" + resolved.model() + "|" + resolved.temperature()
                + "|" + (streaming ? "stream" : "chat");
    }

    private IllegalArgumentException unsupported(String type) {
        return new IllegalArgumentException("Unsupported AI provider type '" + type
                + "'. Use openai-compatible, or add a case in ChatModelFactory.");
    }

    record ResolvedModel(String providerName, String type, String apiKey, String baseUrl,
                         String model, double temperature) {
    }
}
