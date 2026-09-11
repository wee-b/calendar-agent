package com.qiniu.back.module.chat.rag;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(classes = {
        EmbeddingClient.class,
        EmbeddingClientManualTest.TestConfig.class
})
class EmbeddingClientManualTest {

    @Autowired
    private EmbeddingClient client;

    @Autowired
    private EmbeddingProperties props;

    @Test
    void shouldCallConfiguredEmbeddingProvider() {
        List<Float> embedding = client.embed("测试 embedding 模型是否可以正常调用");

        assertFalse(embedding.isEmpty(), "embedding result should not be empty");
        System.out.println("provider=" + props.getEmbeddingProvider()
                + ", model=" + activeModel()
                + ", dimension=" + embedding.size()
                + ", first3=" + embedding.stream().limit(3).toList());
    }

    private String activeModel() {
        return "ollama".equalsIgnoreCase(props.getEmbeddingProvider())
                ? props.getOllama().getModel()
                : props.getAliyun().getModel();
    }

    @TestConfiguration
    @EnableConfigurationProperties(EmbeddingProperties.class)
    static class TestConfig {
        @Bean
        RestTemplate restTemplate() {
            return new RestTemplateBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .readTimeout(Duration.ofSeconds(60))
                    .build();
        }
    }
}
