package com.qiniu.back.module.chat.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    private String ollamaUrl = "http://localhost:11434";
    private String embeddingModel = "bge-m3";
    private String milvusHost = "localhost";
    private int milvusPort = 19530;
    private String milvusCollection = "rag_corpus";
    private int topK = 5;
    private int bm25TopK = 20;
    private int denseTopK = 20;
    private String luceneIndexDir = "./data/lucene-rag-index";

    public String getOllamaUrl() { return ollamaUrl; }
    public void setOllamaUrl(String ollamaUrl) { this.ollamaUrl = ollamaUrl; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public String getMilvusHost() { return milvusHost; }
    public void setMilvusHost(String milvusHost) { this.milvusHost = milvusHost; }

    public int getMilvusPort() { return milvusPort; }
    public void setMilvusPort(int milvusPort) { this.milvusPort = milvusPort; }

    public String getMilvusCollection() { return milvusCollection; }
    public void setMilvusCollection(String milvusCollection) { this.milvusCollection = milvusCollection; }

    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }

    public int getBm25TopK() { return bm25TopK; }
    public void setBm25TopK(int bm25TopK) { this.bm25TopK = bm25TopK; }

    public int getDenseTopK() { return denseTopK; }
    public void setDenseTopK(int denseTopK) { this.denseTopK = denseTopK; }

    public String getLuceneIndexDir() { return luceneIndexDir; }
    public void setLuceneIndexDir(String luceneIndexDir) { this.luceneIndexDir = luceneIndexDir; }
}
