package com.qiniu.back.module.assistant.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class LangChainConfig {

    @Bean(AgentModelBeans.ROUTE)
    @Primary
    public ChatModel routeChatModel(ChatModelFactory factory) {
        return factory.createForAgent(AgentModelBeans.ROUTE_AGENT);
    }

    @Bean(AgentModelBeans.CHAT)
    public ChatModel chatChatModel(ChatModelFactory factory) {
        return factory.createForAgent(AgentModelBeans.CHAT_AGENT);
    }

    @Bean(AgentModelBeans.EXECUTOR)
    public ChatModel executorChatModel(ChatModelFactory factory) {
        return factory.createForAgent(AgentModelBeans.EXECUTOR_AGENT);
    }

    @Bean(AgentModelBeans.PLANNER)
    public ChatModel plannerChatModel(ChatModelFactory factory) {
        return factory.createForAgent(AgentModelBeans.PLANNER_AGENT);
    }

    @Bean(AgentModelBeans.SUMMARY)
    public ChatModel summaryChatModel(ChatModelFactory factory) {
        return factory.createForAgent(AgentModelBeans.SUMMARY_AGENT);
    }

    @Bean(AgentModelBeans.RAG)
    public ChatModel ragChatModel(ChatModelFactory factory) {
        return factory.createForAgent(AgentModelBeans.RAG_AGENT);
    }

    @Bean
    @Primary
    public OpenAiStreamingChatModel streamingChatModel(ChatModelFactory factory) {
        return factory.createStreamingForAgent(AgentModelBeans.CHAT_AGENT);
    }
}
