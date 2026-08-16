package com.company.aics.rag;

import com.company.aics.config.AiProperties;
import com.company.aics.domain.DomainModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 混合向量索引：始终维护内存余弦检索表，并在 Qdrant 可用时镜像写入/检索。
 * Qdrant 不可用时自动降级为纯内存索引，保证本地演示可运行。
 */
@Service
public class VectorIndexService {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexService.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final Map<String, VectorRecord> memoryIndex = new ConcurrentHashMap<>();
    private final EmbeddingClient embeddingClient;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;
    private volatile Boolean qdrantAvailable;

    /**
     * @param embeddingClient 向量化客户端
     * @param aiProperties    Qdrant/collection 配置
     * @param objectMapper    JSON 处理
     */
    public VectorIndexService(
            EmbeddingClient embeddingClient,
            AiProperties aiProperties,
            ObjectMapper objectMapper
    ) {
        this.embeddingClient = embeddingClient;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(20))
                .writeTimeout(Duration.ofSeconds(20))
                .callTimeout(Duration.ofSeconds(25))
                .retryOnConnectionFailure(true)
                .build();
    }

    /**
     * 将单个切块 embedding 后写入内存索引，并尝试同步到 Qdrant。
     */
    public void upsertChunk(DomainModels.DocumentChunk chunk, DomainModels.KnowledgeDocument document) {
        float[] vector = embeddingClient.embed(chunk.content());
        VectorRecord record = new VectorRecord(
                chunk.vectorId(),
                chunk.kbId(),
                chunk.documentId(),
                document.fileName(),
                document.serviceCode(),
                document.priority(),
                chunk.content(),
                chunk,
                document,
                vector
        );
        memoryIndex.put(record.vectorId(), record);
        upsertToQdrant(record);
    }

    /**
     * 对文档全部切块执行 upsert。
     */
    public void upsertDocument(DomainModels.KnowledgeDocument document) {
        for (DomainModels.DocumentChunk chunk : document.chunks()) {
            upsertChunk(chunk, document);
        }
    }

    /**
     * 按文档 ID 删除内存点，并尝试从 Qdrant 删除对应点。
     */
    public void deleteByDocumentId(Long documentId) {
        List<String> vectorIds = memoryIndex.values().stream()
                .filter(record -> documentId.equals(record.documentId()))
                .map(VectorRecord::vectorId)
                .toList();
        for (String vectorId : vectorIds) {
            memoryIndex.remove(vectorId);
        }
        deleteFromQdrant(vectorIds);
    }

    /**
     * 检索：优先 Qdrant；无结果则内存余弦检索，可按 kbId/serviceCodes 过滤。
     * policy 优先级在内存路径额外 +0.05。
     */
    public List<ScoredChunk> search(
            Long kbId,
            String query,
            int topK,
            Set<String> serviceCodes
    ) {
        float[] queryVector = embeddingClient.embed(query);
        List<ScoredChunk> scored = new ArrayList<>();

        List<ScoredChunk> qdrantHits = searchQdrant(kbId, queryVector, topK * 2, serviceCodes);
        if (!qdrantHits.isEmpty()) {
            scored.addAll(qdrantHits);
        } else {
            for (VectorRecord record : memoryIndex.values()) {
                if (kbId != null && !kbId.equals(record.kbId())) {
                    continue;
                }
                if (serviceCodes != null && !serviceCodes.isEmpty()) {
                    if (!StringUtils.hasText(record.serviceCode()) || !serviceCodes.contains(record.serviceCode())) {
                        continue;
                    }
                }
                double cosine = cosine(queryVector, record.vector());
                double priorityBoost = "policy".equalsIgnoreCase(record.priority()) ? 0.05 : 0.0;
                scored.add(new ScoredChunk(record.document(), record.chunk(), cosine + priorityBoost));
            }
        }

        scored.sort(Comparator.comparing(ScoredChunk::score).reversed());
        if (scored.size() <= topK) {
            return scored;
        }
        return scored.subList(0, topK);
    }

    /** 将向量点 PUT 到 Qdrant；失败仅记 debug，不影响内存索引。 */
    private void upsertToQdrant(VectorRecord record) {
        if (!ensureQdrantReady(record.vector().length)) {
            return;
        }
        try {
            ObjectNode point = objectMapper.createObjectNode();
            point.put("id", hashId(record.vectorId()));
            ArrayNode vectorNode = point.putArray("vector");
            for (float value : record.vector()) {
                vectorNode.add(value);
            }
            ObjectNode payload = point.putObject("payload");
            payload.put("vector_id", record.vectorId());
            payload.put("kb_id", record.kbId());
            payload.put("document_id", record.documentId());
            payload.put("document_name", record.documentName());
            payload.put("service_code", record.serviceCode() == null ? "" : record.serviceCode());
            payload.put("priority", record.priority() == null ? "" : record.priority());
            payload.put("content", record.content());

            ObjectNode body = objectMapper.createObjectNode();
            body.putArray("points").add(point);

            Request request = new Request.Builder()
                    .url(qdrantBase() + "/collections/" + aiProperties.getQdrantCollection() + "/points?wait=true")
                    .put(RequestBody.create(objectMapper.writeValueAsBytes(body), JSON))
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.debug("Qdrant upsert skipped: HTTP {}", response.code());
                }
            }
        } catch (Exception ex) {
            log.debug("Qdrant upsert failed: {}", ex.getMessage());
        }
    }

    /** 批量删除 Qdrant 点。 */
    private void deleteFromQdrant(List<String> vectorIds) {
        if (vectorIds.isEmpty() || !ensureQdrantReady(embeddingClient.dimension())) {
            return;
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            ArrayNode points = body.putArray("points");
            for (String vectorId : vectorIds) {
                points.add(hashId(vectorId));
            }
            Request request = new Request.Builder()
                    .url(qdrantBase() + "/collections/" + aiProperties.getQdrantCollection() + "/points/delete?wait=true")
                    .post(RequestBody.create(objectMapper.writeValueAsBytes(body), JSON))
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.debug("Qdrant delete skipped: HTTP {}", response.code());
                }
            }
        } catch (Exception ex) {
            log.debug("Qdrant delete failed: {}", ex.getMessage());
        }
    }

    /**
     * Qdrant 向量搜索；命中后用 payload.vector_id 回查内存记录以还原领域对象。
     */
    private List<ScoredChunk> searchQdrant(
            Long kbId,
            float[] queryVector,
            int topK,
            Set<String> serviceCodes
    ) {
        if (!ensureQdrantReady(queryVector.length)) {
            return List.of();
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            ArrayNode vectorNode = body.putArray("vector");
            for (float value : queryVector) {
                vectorNode.add(value);
            }
            body.put("limit", topK);
            body.put("with_payload", true);

            ObjectNode filter = body.putObject("filter");
            ArrayNode must = filter.putArray("must");
            if (kbId != null) {
                ObjectNode kbMust = must.addObject();
                kbMust.put("key", "kb_id");
                kbMust.putObject("match").put("value", kbId);
            }
            if (serviceCodes != null && !serviceCodes.isEmpty()) {
                ObjectNode serviceMust = must.addObject();
                serviceMust.put("key", "service_code");
                ArrayNode any = serviceMust.putObject("match").putArray("any");
                for (String code : serviceCodes) {
                    any.add(code);
                }
            }
            if (must.isEmpty()) {
                body.remove("filter");
            }

            Request request = new Request.Builder()
                    .url(qdrantBase() + "/collections/" + aiProperties.getQdrantCollection() + "/points/search")
                    .post(RequestBody.create(objectMapper.writeValueAsBytes(body), JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return List.of();
                }
                JsonNode root = objectMapper.readTree(response.body().string());
                JsonNode result = root.path("result");
                if (!result.isArray()) {
                    return List.of();
                }

                List<ScoredChunk> hits = new ArrayList<>();
                for (JsonNode item : result) {
                    String vectorId = item.path("payload").path("vector_id").asText();
                    // Qdrant 仅存向量与 payload；领域切块仍以内存索引为准
                    VectorRecord record = memoryIndex.get(vectorId);
                    if (record == null) {
                        continue;
                    }
                    hits.add(new ScoredChunk(record.document(), record.chunk(), item.path("score").asDouble()));
                }
                return hits;
            }
        } catch (Exception ex) {
            log.debug("Qdrant search failed, using memory index: {}", ex.getMessage());
            return List.of();
        }
    }

    /**
     * 懒检测/创建 Qdrant collection；结果缓存到 {@code qdrantAvailable}。
     */
    private boolean ensureQdrantReady(int vectorSize) {
        if (!StringUtils.hasText(aiProperties.getQdrantUrl())) {
            return false;
        }
        Boolean cached = qdrantAvailable;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (qdrantAvailable != null) {
                return qdrantAvailable;
            }
            try {
                if (!collectionExists()) {
                    createCollection(vectorSize);
                }
                qdrantAvailable = true;
                log.info("Qdrant collection ready: {}", aiProperties.getQdrantCollection());
            } catch (Exception ex) {
                qdrantAvailable = false;
                log.info("Qdrant unavailable, using in-memory vector index only: {}", ex.getMessage());
            }
            return qdrantAvailable;
        }
    }

    /** 探测 collection 是否已存在。 */
    private boolean collectionExists() throws IOException {
        Request request = new Request.Builder()
                .url(qdrantBase() + "/collections/" + aiProperties.getQdrantCollection())
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            return response.isSuccessful();
        }
    }

    /** 创建 Cosine 距离的向量 collection。 */
    private void createCollection(int vectorSize) throws IOException {
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode vectors = body.putObject("vectors");
        vectors.put("size", vectorSize);
        vectors.put("distance", "Cosine");
        Request request = new Request.Builder()
                .url(qdrantBase() + "/collections/" + aiProperties.getQdrantCollection())
                .put(RequestBody.create(objectMapper.writeValueAsBytes(body), JSON))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() && response.code() != 409) {
                String bodyText = response.body() == null ? "" : response.body().string();
                throw new IOException("Create collection failed: HTTP " + response.code() + " " + bodyText);
            }
        }
    }

    /** @return 去掉末尾斜杠的 Qdrant 基础 URL */
    private String qdrantBase() {
        String url = aiProperties.getQdrantUrl();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 将字符串 vectorId 映射为 Qdrant 可用的数值 ID。 */
    private long hashId(String vectorId) {
        return Math.floorMod(vectorId.hashCode(), Integer.MAX_VALUE);
    }

    /** 计算两向量余弦相似度。 */
    private double cosine(float[] left, float[] right) {
        int size = Math.min(left.length, right.length);
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < size; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm <= 1e-12 || rightNorm <= 1e-12) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    /** 检索命中：文档、切块与分数。 */
    public record ScoredChunk(
            DomainModels.KnowledgeDocument document,
            DomainModels.DocumentChunk chunk,
            double score
    ) {
    }

    /** 内存索引记录：向量 + 元数据 + 领域对象引用。 */
    private record VectorRecord(
            String vectorId,
            Long kbId,
            Long documentId,
            String documentName,
            String serviceCode,
            String priority,
            String content,
            DomainModels.DocumentChunk chunk,
            DomainModels.KnowledgeDocument document,
            float[] vector
    ) {
    }
}
