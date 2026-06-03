package com.qiniu.back.module.chat.rag;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
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
    public MilvusServiceClient milvusClient(RagProperties props) {
        return new MilvusServiceClient(
                ConnectParam.newBuilder()
                        .withHost(props.getMilvusHost())
                        .withPort(props.getMilvusPort())
                        .build()
        );
    }
}
