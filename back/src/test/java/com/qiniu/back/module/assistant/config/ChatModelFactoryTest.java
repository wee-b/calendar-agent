package com.qiniu.back.module.assistant.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatModelFactoryTest {

    @Test
    void plannerUsesDeepSeekCredentials() {
        ChatModelFactory factory = new ChatModelFactory(sampleProperties());

        ChatModelFactory.ResolvedModel resolved = factory.resolve("planner");

        assertEquals("deepseek", resolved.providerName());
        assertEquals("deepseek-v4-pro", resolved.model());
        assertEquals("deepseek-key", resolved.apiKey());
        assertEquals("https://api.deepseek.com", resolved.baseUrl());
    }

    @Test
    void routeUsesDashscopeCredentials() {
        ChatModelFactory factory = new ChatModelFactory(sampleProperties());

        ChatModelFactory.ResolvedModel resolved = factory.resolve("route");

        assertEquals("dashscope", resolved.providerName());
        assertEquals("qwen3.7-plus", resolved.model());
        assertEquals("aliyun-key", resolved.apiKey());
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", resolved.baseUrl());
    }

    @Test
    void fallsBackToDefaultProviderWhenAgentOmitsIt() {
        AiProperties properties = sampleProperties();
        properties.getAgents().get("chat").setProvider(null);
        ChatModelFactory factory = new ChatModelFactory(properties);

        ChatModelFactory.ResolvedModel resolved = factory.resolve("chat");

        assertEquals("dashscope", resolved.providerName());
        assertEquals("aliyun-key", resolved.apiKey());
    }

    @Test
    void rejectsProviderWithoutOwnCredentials() {
        AiProperties properties = sampleProperties();
        properties.getProviders().get("deepseek").setApiKey(null);
        ChatModelFactory factory = new ChatModelFactory(properties);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> factory.resolve("planner"));
        assertTrue(error.getMessage().contains("deepseek"));
        assertTrue(error.getMessage().contains("api-key"));
    }

    @Test
    void rejectsUnknownProviderType() {
        AiProperties properties = sampleProperties();
        properties.getProviders().get("dashscope").setType("ollama");
        ChatModelFactory factory = new ChatModelFactory(properties);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.createForAgent("route"));
        assertTrue(error.getMessage().contains("ollama"));
    }

    private AiProperties sampleProperties() {
        AiProperties properties = new AiProperties();
        properties.setDefaultProvider("dashscope");

        AiProperties.Provider dashscope = new AiProperties.Provider();
        dashscope.setType("openai-compatible");
        dashscope.setApiKey("aliyun-key");
        dashscope.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        properties.getProviders().put("dashscope", dashscope);

        AiProperties.Provider deepseek = new AiProperties.Provider();
        deepseek.setType("openai-compatible");
        deepseek.setApiKey("deepseek-key");
        deepseek.setBaseUrl("https://api.deepseek.com");
        properties.getProviders().put("deepseek", deepseek);

        properties.getAgents().put("route", agent("dashscope", "qwen3.7-plus", 0.1));
        properties.getAgents().put("chat", agent("deepseek", "deepseek-flash", 0.3));
        properties.getAgents().put("planner", agent("deepseek", "deepseek-v4-pro", 0.1));
        return properties;
    }

    private AiProperties.AgentModel agent(String provider, String model, double temperature) {
        AiProperties.AgentModel spec = new AiProperties.AgentModel();
        spec.setProvider(provider);
        spec.setModel(model);
        spec.setTemperature(temperature);
        return spec;
    }
}
