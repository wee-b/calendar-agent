package com.qiniu.back.config;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class LangChainConfig {

    @Value("${app.ai.api-key}")
    private String apiKey;

    @Value("${app.ai.base-url}")
    private String baseUrl;

    @Value("${app.ai.model}")
    private String model;

    @Value("${app.ai.planner-model}")
    private String plannerModel;

    @Bean
    @Primary
    public OpenAiChatModel chatModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .temperature(0.7)
                .build();
    }

    @Bean
    @Primary
    public OpenAiStreamingChatModel streamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .temperature(0.7)
                .build();
    }

    @Bean("plannerChatModel")
    public OpenAiChatModel plannerChatModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(plannerModel)
                .temperature(0.1)
                .build();
    }
}
