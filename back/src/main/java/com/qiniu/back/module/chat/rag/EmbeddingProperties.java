package com.qiniu.back.module.chat.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.rag")
public class EmbeddingProperties {

    private String embeddingProvider = "ollama";

    private Ollama ollama = new Ollama();

    private Aliyun aliyun = new Aliyun();

    @Data
    public static class Ollama {
        private String url = "http://localhost:11434";
        private String model = "bge-m3";
    }

    @Data
    public static class Aliyun {
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String apiKey;
        private String model = "text-embedding-v4";
    }
}
