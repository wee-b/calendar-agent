package com.qiniu.back.module.assistant.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private int topK = 3;
    private int bm25TopK = 10;
    private int denseTopK = 10;
    private String luceneIndexDir = "./data/lucene-rag-index";

    private boolean rerankEnabled = false;
    private double recallThreshold = 0.015;
    private int embeddingCacheTtl = 86_400;
    private int resultCacheTtl = 1_800;

}
