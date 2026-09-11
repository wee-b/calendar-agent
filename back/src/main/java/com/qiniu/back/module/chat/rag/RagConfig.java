package com.qiniu.back.module.chat.rag;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RagConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(120))
                .build();
    }

    @Bean
    public QdrantClient qdrantClient(QdrantProperties props) {
        QdrantGrpcClient grpcClient = QdrantGrpcClient.newBuilder(
                props.getHost(), props.getPort(), false
        ).build();
        return new QdrantClient(grpcClient);
    }
}
