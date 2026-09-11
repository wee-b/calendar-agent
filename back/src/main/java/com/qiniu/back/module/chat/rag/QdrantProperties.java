package com.qiniu.back.module.chat.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.qdrant")
public class QdrantProperties {

    private String host = "localhost";

    private int port = 6334;

    private String collection = "rag_corpus";
}
