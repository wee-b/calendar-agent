package com.qiniu.back.module.assistant.rag;

import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.Vector;
import io.qdrant.client.grpc.Points.Vectors;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 手动导入 RAG Markdown 语料。
 *
 * <p>Embedding、模型、API key 与 Qdrant 地址全部复用 Spring Boot 配置。
 * 直接运行该测试会调用已配置的 Embedding 接口并写入 Qdrant；设置
 * {@code -Drag.corpus.dry-run=true} 时只验证分块和去重。</p>
 */
@SpringBootTest(classes = {
        EmbeddingClient.class,
        RagCorpusImportManualTest.TestConfig.class
})
class RagCorpusImportManualTest {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern FRONT_MATTER = Pattern.compile("(?s)^---\\R.*?\\R---\\R");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern EMPHASIS = Pattern.compile("\\*{1,3}([^*]+)\\*{1,3}");
    private static final Pattern LINK = Pattern.compile("\\[([^]]+)]\\([^)]+\\)");
    private static final Pattern IMAGE = Pattern.compile("!\\[[^]]*]\\([^)]+\\)");
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[。；！？\\n])");

    private static final int MAX_CHUNK_LENGTH = 500;
    private static final double TF_IDF_THRESHOLD = 0.92;
    private static final int SIMHASH_MAX_DISTANCE = 3;
    private static final double JACCARD_THRESHOLD = 0.80;
    private static final int UPSERT_BATCH_SIZE = 32;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private QdrantClient qdrantClient;

    @Autowired
    private QdrantProperties qdrantProperties;

    @Autowired
    private Environment environment;

    @Test
    void importMarkdownCorpusToQdrant() throws Exception {
        Path corpusDirectory = resolveCorpusDirectory();
        List<CorpusChunk> loaded = loadMarkdownCorpus(corpusDirectory);
        List<CorpusChunk> chunks = deduplicate(loaded);

        assertFalse(chunks.isEmpty(), "没有找到可导入的语料: " + corpusDirectory);
        System.out.printf("语料加载完成: 原始 %d chunks，去重后 %d chunks%n",
                loaded.size(), chunks.size());

        if (Boolean.getBoolean("rag.corpus.dry-run")) {
            System.out.println("dry-run 完成：未调用 Embedding 接口，未写入 Qdrant");
            return;
        }

        List<Float> firstEmbedding = requireEmbedding(chunks.get(0));
        ensureCollection(firstEmbedding.size());

        List<PointStruct> batch = new ArrayList<>(UPSERT_BATCH_SIZE);
        for (int i = 0; i < chunks.size(); i++) {
            CorpusChunk chunk = chunks.get(i);
            List<Float> embedding = i == 0 ? firstEmbedding : requireEmbedding(chunk);
            batch.add(toPoint(chunk, embedding));

            if (batch.size() == UPSERT_BATCH_SIZE || i == chunks.size() - 1) {
                qdrantClient.upsertAsync(qdrantProperties.getCollection(), List.copyOf(batch)).get();
                System.out.printf("Qdrant 写入进度: %d/%d%n", i + 1, chunks.size());
                batch.clear();
            }
        }

        System.out.printf("导入完成: collection=%s, chunks=%d, embeddingDimension=%d%n",
                qdrantProperties.getCollection(), chunks.size(), firstEmbedding.size());
    }

    private Path resolveCorpusDirectory() {
        String configured = environment.getProperty("rag.corpus.source-dir");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }

        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path projectRoot = "back".equalsIgnoreCase(workingDirectory.getFileName().toString())
                ? workingDirectory.getParent()
                : workingDirectory;
        return projectRoot.resolve("back/src/main/resources/rag_data").normalize();
    }

    private List<CorpusChunk> loadMarkdownCorpus(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("语料目录不存在: " + directory);
        }

        try (Stream<Path> files = Files.list(directory)) {
            List<Path> markdownFiles = files
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .sorted()
                    .toList();

            List<CorpusChunk> result = new ArrayList<>();
            for (Path markdownFile : markdownFiles) {
                List<CorpusChunk> fileChunks = chunkMarkdown(markdownFile);
                result.addAll(fileChunks);
                System.out.printf("%s: %d chunks%n", markdownFile.getFileName(), fileChunks.size());
            }
            return result;
        }
    }

    private List<CorpusChunk> chunkMarkdown(Path file) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        raw = FRONT_MATTER.matcher(raw).replaceFirst("");

        List<String> headingStack = new ArrayList<>();
        List<SectionText> paragraphs = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                int level = heading.group(1).length();
                while (headingStack.size() >= level) {
                    headingStack.remove(headingStack.size() - 1);
                }
                headingStack.add(heading.group(2).trim());
                continue;
            }

            String stripped = line.trim();
            if (stripped.isEmpty() || stripped.matches("^[-*_]{3,}$")) {
                continue;
            }
            if (stripped.startsWith("> ") && stripped.contains("注：")) {
                continue;
            }

            String cleaned = cleanMarkdown(stripped);
            if (!cleaned.isEmpty()) {
                paragraphs.add(new SectionText(List.copyOf(headingStack), cleaned));
            }
        }

        List<SectionText> merged = new ArrayList<>();
        for (SectionText paragraph : paragraphs) {
            if (!merged.isEmpty()
                    && merged.get(merged.size() - 1).headings().equals(paragraph.headings())) {
                SectionText previous = merged.remove(merged.size() - 1);
                merged.add(new SectionText(previous.headings(), previous.text() + "\n" + paragraph.text()));
            } else {
                merged.add(paragraph);
            }
        }

        List<CorpusChunk> chunks = new ArrayList<>();
        for (SectionText section : merged) {
            String sectionPath = section.headings().isEmpty()
                    ? "(无标题)"
                    : String.join(" > ", section.headings());
            for (String fragment : splitLongParagraph(section.text())) {
                if (fragment.length() >= 30) {
                    chunks.add(new CorpusChunk(
                            file.getFileName().toString(), sectionPath, fragment, fragment.length()));
                }
            }
        }
        return chunks;
    }

    private String cleanMarkdown(String text) {
        String cleaned = IMAGE.matcher(text).replaceAll("");
        cleaned = LINK.matcher(cleaned).replaceAll("$1");
        cleaned = INLINE_CODE.matcher(cleaned).replaceAll("$1");
        cleaned = EMPHASIS.matcher(cleaned).replaceAll("$1");
        cleaned = cleaned.replaceFirst("^>\\s?", "");
        return cleaned.trim();
    }

    private List<String> splitLongParagraph(String text) {
        if (text.length() <= MAX_CHUNK_LENGTH) {
            return List.of(text);
        }

        List<String> fragments = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (String sentence : SENTENCE_BOUNDARY.split(text)) {
            if (buffer.length() > 0 && buffer.length() + sentence.length() > MAX_CHUNK_LENGTH) {
                fragments.add(buffer.toString().trim());
                buffer.setLength(0);
            }
            buffer.append(sentence);
        }
        if (!buffer.isEmpty()) {
            fragments.add(buffer.toString().trim());
        }
        return fragments.isEmpty() ? List.of(text) : fragments;
    }

    private List<CorpusChunk> deduplicate(List<CorpusChunk> chunks) throws IOException {
        List<List<String>> tokenized = new ArrayList<>(chunks.size());
        try (Analyzer analyzer = new SmartChineseAnalyzer()) {
            for (CorpusChunk chunk : chunks) {
                tokenized.add(tokenize(analyzer, chunk.text()));
            }
        }

        List<Map<String, Double>> tfIdfVectors = buildTfIdfVectors(tokenized);
        List<java.math.BigInteger> simHashes = tokenized.stream().map(this::simHash).toList();
        List<Set<String>> trigrams = chunks.stream().map(chunk -> trigrams(chunk.text())).toList();

        Set<Integer> removed = new HashSet<>();
        for (int i = 0; i < chunks.size(); i++) {
            if (removed.contains(i)) {
                continue;
            }
            for (int j = i + 1; j < chunks.size(); j++) {
                if (removed.contains(j)) {
                    continue;
                }
                boolean duplicate = cosine(tfIdfVectors.get(i), tfIdfVectors.get(j)) >= TF_IDF_THRESHOLD
                        || simHashes.get(i).xor(simHashes.get(j)).bitCount() <= SIMHASH_MAX_DISTANCE
                        || jaccard(trigrams.get(i), trigrams.get(j)) >= JACCARD_THRESHOLD;
                if (duplicate) {
                    removed.add(j);
                }
            }
        }

        List<CorpusChunk> kept = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            if (!removed.contains(i)) {
                kept.add(chunks.get(i));
            }
        }
        return kept;
    }

    private List<String> tokenize(Analyzer analyzer, String text) throws IOException {
        List<String> words = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream("text", new StringReader(text))) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                String value = term.toString().trim();
                if (!value.isEmpty()) {
                    words.add(value);
                }
            }
            stream.end();
        }

        List<String> features = new ArrayList<>(words);
        for (int i = 0; i + 1 < words.size(); i++) {
            features.add(words.get(i) + "\u0000" + words.get(i + 1));
        }
        return features;
    }

    private List<Map<String, Double>> buildTfIdfVectors(List<List<String>> documents) {
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (List<String> document : documents) {
            for (String term : new HashSet<>(document)) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }

        int documentCount = documents.size();
        List<Map<String, Double>> vectors = new ArrayList<>(documentCount);
        for (List<String> document : documents) {
            Map<String, Integer> termFrequency = new HashMap<>();
            document.forEach(term -> termFrequency.merge(term, 1, Integer::sum));

            Map<String, Double> vector = new HashMap<>();
            termFrequency.forEach((term, frequency) -> {
                double idf = Math.log((documentCount + 1.0)
                        / (documentFrequency.getOrDefault(term, 0) + 1.0)) + 1.0;
                vector.put(term, frequency * idf);
            });
            vectors.add(vector);
        }
        return vectors;
    }

    private double cosine(Map<String, Double> left, Map<String, Double> right) {
        Map<String, Double> smaller = left.size() <= right.size() ? left : right;
        Map<String, Double> larger = smaller == left ? right : left;
        double dot = smaller.entrySet().stream()
                .mapToDouble(entry -> entry.getValue() * larger.getOrDefault(entry.getKey(), 0.0))
                .sum();
        double leftNorm = Math.sqrt(left.values().stream().mapToDouble(v -> v * v).sum());
        double rightNorm = Math.sqrt(right.values().stream().mapToDouble(v -> v * v).sum());
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / (leftNorm * rightNorm);
    }

    private java.math.BigInteger simHash(List<String> tokens) {
        int[] weights = new int[128];
        for (String token : tokens) {
            byte[] digest = md5(token);
            for (int bit = 0; bit < 128; bit++) {
                boolean set = (digest[bit / 8] & (1 << (7 - bit % 8))) != 0;
                weights[bit] += set ? 1 : -1;
            }
        }

        java.math.BigInteger fingerprint = java.math.BigInteger.ZERO;
        for (int bit = 0; bit < weights.length; bit++) {
            if (weights[bit] > 0) {
                fingerprint = fingerprint.setBit(bit);
            }
        }
        return fingerprint;
    }

    private byte[] md5(String value) {
        try {
            return MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 不支持 MD5", e);
        }
    }

    private Set<String> trigrams(String text) {
        String normalized = text.replaceAll("\\s+", "");
        if (normalized.length() < 3) {
            return Set.of(normalized);
        }
        Set<String> result = new HashSet<>();
        for (int i = 0; i <= normalized.length() - 3; i++) {
            result.add(normalized.substring(i, i + 3));
        }
        return result;
    }

    private double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() && right.isEmpty()) {
            return 1;
        }
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    private List<Float> requireEmbedding(CorpusChunk chunk) {
        List<Float> embedding = embeddingClient.embed(chunk.text());
        if (embedding == null || embedding.isEmpty()) {
            throw new IllegalStateException("Embedding 返回空向量，请检查后端 provider、API key 与模型配置");
        }
        return embedding;
    }

    private void ensureCollection(int dimension) throws Exception {
        String collection = qdrantProperties.getCollection();
        if (qdrantClient.collectionExistsAsync(collection).get()) {
            System.out.printf("使用已有 Qdrant collection: %s%n", collection);
            return;
        }

        VectorParams vectors = VectorParams.newBuilder()
                .setSize(dimension)
                .setDistance(Distance.Cosine)
                .build();
        qdrantClient.createCollectionAsync(collection, vectors).get();
        System.out.printf("已创建 Qdrant collection: %s (%d dimensions)%n", collection, dimension);
    }

    private PointStruct toPoint(CorpusChunk chunk, List<Float> embedding) {
        String identity = String.join("\u0000", chunk.source(), chunk.section(), chunk.text());
        UUID id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        Map<String, Value> payload = new LinkedHashMap<>();
        payload.put("source", ValueFactory.value(chunk.source()));
        payload.put("section", ValueFactory.value(chunk.section()));
        payload.put("text", ValueFactory.value(chunk.text()));
        payload.put("char_count", ValueFactory.value(chunk.charCount()));

        return PointStruct.newBuilder()
                .setId(PointIdFactory.id(id))
                .setVectors(Vectors.newBuilder()
                        .setVector(Vector.newBuilder().addAllData(embedding).build())
                        .build())
                .putAllPayload(payload)
                .build();
    }

    private record SectionText(List<String> headings, String text) {
    }

    private record CorpusChunk(String source, String section, String text, int charCount) {
    }

    @TestConfiguration
    @EnableConfigurationProperties({EmbeddingProperties.class, QdrantProperties.class})
    static class TestConfig {

        @Bean
        RestTemplate restTemplate() {
            return new RestTemplateBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .readTimeout(Duration.ofSeconds(120))
                    .build();
        }

        @Bean
        QdrantClient qdrantClient(QdrantProperties properties) {
            return new QdrantClient(QdrantGrpcClient.newBuilder(
                    properties.getHost(), properties.getPort(), false).build());
        }
    }
}
