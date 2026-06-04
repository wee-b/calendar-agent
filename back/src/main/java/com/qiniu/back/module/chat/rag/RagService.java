package com.qiniu.back.module.chat.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiniu.back.constant.RedisConstant;
import com.qiniu.back.util.DigestUtil;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.MMapDirectory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RAG 混合检索引擎：BM25(Lucene) + BGE-M3(Milvus) → RRF 融合 → LLM Rerank
 */
@Slf4j
@Service
public class RagService {

    private static final int RRF_K = 60;

    private final RagProperties props;
    private final RestTemplate rest;
    private final MilvusServiceClient milvus;
    private final ChatModel chatModel;// 现有 4 个参数：props, rest, milvus, chatModel

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;



    private final AtomicReference<IndexSearcher> bm25Searcher = new AtomicReference<>();
    private SmartChineseAnalyzer analyzer;

    public RagService(RagProperties props, RestTemplate rest,
                      MilvusServiceClient milvus, ChatModel chatModel,StringRedisTemplate redis,ObjectMapper objectMapper) {
        this.props = props;
        this.rest = rest;
        this.milvus = milvus;
        this.chatModel = chatModel;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    // ========================================================================
    // 初始化：从 Milvus 拉取全量文档构建 Lucene BM25 索引
    // ========================================================================

    @PostConstruct
    void init() {
        analyzer = new SmartChineseAnalyzer();
        try {
            buildBm25Index();
        } catch (Exception e) {
            log.warn("BM25 索引初始化失败，RAG 仅使用语义检索: {}", e.getMessage());
        }
    }

    synchronized void buildBm25Index() throws IOException {
        List<Map<String, Object>> docs = loadAllFromMilvus();
        if (docs.isEmpty()) {
            log.warn("[RAG] Milvus 中无文档，BM25 索引为空");
            return;
        }

        Path indexPath = Path.of(props.getLuceneIndexDir());
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        try (MMapDirectory dir = new MMapDirectory(indexPath);
             IndexWriter writer = new IndexWriter(dir, config)) {

            writer.deleteAll();
            for (Map<String, Object> doc : docs) {
                Document luceneDoc = new Document();
                luceneDoc.add(new StringField("id", str(doc, "id"), Field.Store.YES));
                luceneDoc.add(new StoredField("source", str(doc, "source")));
                luceneDoc.add(new StoredField("section", str(doc, "section")));
                luceneDoc.add(new TextField("text", str(doc, "text"), Field.Store.YES));
                luceneDoc.add(new IntField("char_count", intVal(doc, "char_count"), Field.Store.YES));
                writer.addDocument(luceneDoc);
            }
            writer.commit();
        }

        DirectoryReader reader = DirectoryReader.open(MMapDirectory.open(indexPath));
        bm25Searcher.set(new IndexSearcher(reader));
        log.info("[RAG] BM25 索引构建完成: {} 篇文档", reader.numDocs());
    }

    private List<Map<String, Object>> loadAllFromMilvus() {
        QueryParam param = QueryParam.newBuilder()
                .withCollectionName(props.getMilvusCollection())
                .withExpr("id != \"\"")
                .withOutFields(List.of("id", "source", "section", "text", "char_count"))
                .withLimit(10000L)
                .build();

        R<io.milvus.grpc.QueryResults> result = milvus.query(param);
        if (result.getStatus() != 0) {
            log.error("[RAG] Milvus 全量查询失败: {}", result.getMessage());
            return List.of();
        }

        QueryResultsWrapper wrapper = new QueryResultsWrapper(result.getData());
        List<Map<String, Object>> docs = new ArrayList<>();
        for (QueryResultsWrapper.RowRecord row : wrapper.getRowRecords()) {
            docs.add(row.getFieldValues());
        }
        return docs;
    }

    // ========================================================================
    // 主入口：混合检索
    // ========================================================================

    /**
     * 混合检索：BM25 + Dense → RRF → LLM Rerank → Top-K
     */
    public List<RagHit> search(String query) {

        String cacheKey = RedisConstant.Rag_Cache_Key + DigestUtil.md5(query);
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                List<RagHit> hits = objectMapper.readValue(cached,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, RagHit.class));
                log.info("[RAG] L2 缓存命中, {} 条结果", hits.size());
                return hits;
            }
        } catch (Exception e) {
            log.warn("[RAG] 读取 RAG 结果缓存失败: {}", e.getMessage());
        }

        List<RagHit> bm25Hits = searchBm25(query);
        List<RagHit> denseHits = searchDense(query);
        log.info("[RAG] BM25={}, Dense={}", bm25Hits.size(), denseHits.size());

        List<RagHit> fused = rrfFusion(bm25Hits, denseHits, props.getTopK() * 3);
        // 阈值过滤
        fused = fused.stream().filter(h -> h.getScore() >= props.getRecallThreshold()).toList();
        log.info("[RAG] RRF 融合后={}", fused.size());

        if (fused.isEmpty()) {
            return fused;
        }

        List<RagHit> ranked = rerank(query, fused);
        int finalK = Math.min(props.getTopK(), ranked.size());
        List<RagHit> topK = ranked.subList(0, finalK);
        log.info("[RAG] Rerank → Top-{}: scores={}", props.getTopK(),
                topK.stream().map(h -> String.format("%.3f", h.getScore())).toList());

        // 返回前写缓存
        try {
            redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(topK),
                    Duration.ofSeconds(props.getResultCacheTtl()));
        } catch (Exception e) {
            log.warn("[RAG] 写入 RAG 结果缓存失败: {}", e.getMessage());
        }
        return topK;
    }

    // ========================================================================
    // BM25 检索 (Lucene)
    // ========================================================================

    List<RagHit> searchBm25(String query) {
        IndexSearcher searcher = bm25Searcher.get();
        if (searcher == null) return List.of();

        try {
            QueryParser parser = new QueryParser("text", analyzer);
            var q = parser.parse(QueryParser.escape(query));
            TopDocs topDocs = searcher.search(q, props.getBm25TopK());

            List<RagHit> hits = new ArrayList<>();
            float maxScore = 0;
            for (ScoreDoc sd : topDocs.scoreDocs) {
                if (sd.score > maxScore) maxScore = sd.score;
            }
            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(sd.doc);
                float normScore = maxScore > 0 ? sd.score / maxScore : 0;
                hits.add(new RagHit(
                        doc.get("id"), doc.get("source"), doc.get("section"),
                        doc.get("text"), Integer.parseInt(doc.get("char_count")), normScore
                ));
            }
            return hits;
        } catch (Exception e) {
            log.warn("[RAG] BM25 检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ========================================================================
    // Dense 检索 (Ollama BGE-M3 → Milvus)
    // ========================================================================

    List<RagHit> searchDense(String query) {
        List<Float> embedding = embedQuery(query);
        if (embedding == null || embedding.isEmpty()) return List.of();

        SearchParam param = SearchParam.newBuilder()
                .withCollectionName(props.getMilvusCollection())
                .withVectorFieldName("embedding")
                .withFloatVectors(List.of(embedding))
                .withTopK(props.getDenseTopK())
                .withMetricType(MetricType.COSINE)
                .withOutFields(List.of("id", "source", "section", "text", "char_count"))
                .build();

        R<io.milvus.grpc.SearchResults> result = milvus.search(param);
        if (result.getStatus() != 0) {
            log.warn("[RAG] Milvus 语义搜索失败: {}", result.getMessage());
            return List.of();
        }

        SearchResultsWrapper wrapper = new SearchResultsWrapper(result.getData().getResults());
        List<SearchResultsWrapper.IDScore> idScores = wrapper.getIDScore(0);
        List<RagHit> hits = new ArrayList<>();
        for (SearchResultsWrapper.IDScore is : idScores) {
            hits.add(new RagHit(
                    is.getStrID(),
                    String.valueOf(is.get("source")),
                    String.valueOf(is.get("section")),
                    String.valueOf(is.get("text")),
                    ((Number) is.get("char_count")).intValue(),
                    is.getScore()
            ));
        }
        return hits;
    }

    // ========================================================================
    // BGE-M3 Embedding (Ollama)
    // ========================================================================

    @SuppressWarnings("unchecked")
    List<Float> embedQuery(String text) {

        // 1. 检查 Redis 缓存
        String cacheKey = RedisConstant.Rag_Emb_Key + DigestUtil.md5(text);
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Float.class));
            }
        } catch (Exception e) {
            log.warn("[RAG] 读取 embedding 缓存失败: {}", e.getMessage());
        }

        // 2. 缓存未命中，请求 Ollama
        String url = props.getOllamaUrl() + "/api/embeddings";
        Map<String, String> body = Map.of("model", props.getEmbeddingModel(), "prompt", text);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> req = new HttpEntity<>(body, headers);

            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    url, org.springframework.http.HttpMethod.POST, req,
                    new ParameterizedTypeReference<>() {}
            );

            Map<String, Object> respBody = resp.getBody();
            if (respBody == null || !respBody.containsKey("embedding")) return List.of();

            Object emb = respBody.get("embedding");
            if (emb instanceof List<?> list) {
                List<Float> floats = new ArrayList<>();
                for (Object item : list) {
                    floats.add(((Number) item).floatValue());
                }
                // 3. 写入 Redis 缓存
                try {
                    redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(floats),
                            Duration.ofSeconds(props.getEmbeddingCacheTtl()));
                } catch (Exception e) {
                    log.warn("[RAG] 写入 embedding 缓存失败: {}", e.getMessage());
                }
                return floats;
            }
            return List.of();
        } catch (RestClientException e) {
            log.warn("[RAG] Ollama embedding 请求失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ========================================================================
    // RRF 融合
    // ========================================================================

    List<RagHit> rrfFusion(List<RagHit> listA, List<RagHit> listB, int maxResults) {
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, RagHit> hitMap = new LinkedHashMap<>();

        applyRRF(listA, rrfScores, hitMap);
        applyRRF(listB, rrfScores, hitMap);

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(maxResults)
                .map(e -> {
                    RagHit h = hitMap.get(e.getKey());
                    h.setScore(e.getValue().floatValue());
                    return h;
                })
                .toList();
    }

    private void applyRRF(List<RagHit> hits, Map<String, Double> scores, Map<String, RagHit> map) {
        for (int i = 0; i < hits.size(); i++) {
            RagHit h = hits.get(i);
            double rrf = 1.0 / (RRF_K + i + 1);
            scores.merge(h.getId(), rrf, Double::sum);
            map.putIfAbsent(h.getId(), h);
        }
    }

    // ========================================================================
    // LLM Rerank（DeepSeek 单次评分）
    // ========================================================================

    List<RagHit> rerank(String query, List<RagHit> candidates) {
        if (candidates.size() <= props.getTopK()) {
            candidates.sort(Comparator.reverseOrder());
            return candidates;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            sb.append("[").append(i).append("] ").append(candidates.get(i).getText()).append("\n\n");
        }

        String prompt = String.format(
                "你是一个日程规划相关性评估助手。\n"
                        + "用户查询: %s\n\n"
                        + "以下是候选语料片段，请评估每条与用户查询的相关性，输出排序结果。\n"
                        + "只输出 JSON 数组，包含索引号，最相关的排前面。\n"
                        + "示例输出: [3, 0, 7, 1, 5]\n\n"
                        + "候选语料:\n%s",
                query, sb.toString()
        );

        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(new UserMessage(prompt)))
                    .temperature(0.0)
                    .build();
            String response = chatModel.chat(request).aiMessage().text();
            List<Integer> order = parseRerankResponse(response, candidates.size());
            if (order.isEmpty()) {
                candidates.sort(Comparator.reverseOrder());
                return candidates;
            }

            List<RagHit> reranked = new ArrayList<>();
            for (int i = 0; i < order.size(); i++) {
                int idx = order.get(i);
                if (idx >= 0 && idx < candidates.size()) {
                    RagHit h = candidates.get(idx);
                    h.setScore(1.0f - (float) i / order.size());
                    reranked.add(h);
                }
            }
            for (int i = 0; i < candidates.size(); i++) {
                if (!order.contains(i)) {
                    RagHit h = candidates.get(i);
                    h.setScore(0.1f);
                    reranked.add(h);
                }
            }
            return reranked;
        } catch (Exception e) {
            log.warn("[RAG] Rerank 失败，回退 RRF 排序: {}", e.getMessage());
            candidates.sort(Comparator.reverseOrder());
            return candidates;
        }
    }

    private List<Integer> parseRerankResponse(String text, int maxIndex) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end < 0 || end <= start) return List.of();

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            List<Integer> ids = mapper.readValue(text.substring(start, end + 1),
                    mapper.getTypeFactory().constructCollectionType(List.class, Integer.class));
            return ids.stream().filter(i -> i >= 0 && i < maxIndex).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    // ========================================================================
    // 辅助
    // ========================================================================

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : "";
    }

    private static int intVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        return 0;
    }
}
