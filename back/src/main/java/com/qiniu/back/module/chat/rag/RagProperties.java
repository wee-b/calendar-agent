package com.qiniu.back.module.chat.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    private String ollamaUrl = "http://localhost:11434";
    private String embeddingModel = "bge-m3";
    private String milvusHost = "localhost";
    private int milvusPort = 19530;
    private String milvusCollection = "rag_corpus";
    private int topK = 3;
    private int bm25TopK = 10;
    private int denseTopK = 10;
    private String luceneIndexDir = "./data/lucene-rag-index";

    private double recallThreshold = 0.0;     // + getter/setter
    private int embeddingCacheTtl = 360;      // + getter/setter

    // 在现有字段后追加
    private int resultCacheTtl = 180;  // RAG 结果缓存，默认 30 分钟

}
