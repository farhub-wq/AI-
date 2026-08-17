package com.company.aics.rag;

import com.company.aics.config.AiProperties;
import com.company.aics.domain.DomainModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 自研本地扁平向量索引（Faiss IndexFlat 风格精确余弦；JSON 落盘）。
 * <p>
 * 对齐题目 FAQ「本地文件模式、无需独立向量服务」：未引入 Faiss/Chroma 原生库，
 * 用内存 {@link ConcurrentHashMap} + {@code index.json} 即可完整跑通 RAG/Agent。
 * 落盘经 {@code persistLock} 写临时文件再原子替换，避免半写损坏。
 * <p>
 * 检索侧对 policy 文档 +0.05 轻偏置；最终「政策优先占预算」仍由
 * {@link EvidenceGovernanceService} 分层打包负责，两者分工：召回偏好 vs 入模配额。
 */
@Service
public class VectorIndexService {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexService.class);

    private final Map<String, VectorRecord> memoryIndex = new ConcurrentHashMap<>();
    private final EmbeddingClient embeddingClient;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final Object persistLock = new Object();

    /**
     * @param embeddingClient 向量化客户端
     * @param aiProperties    本地向量索引目录等配置
     * @param objectMapper    JSON 读写
     */
    public VectorIndexService(
            EmbeddingClient embeddingClient,
            AiProperties aiProperties,
            ObjectMapper objectMapper
    ) {
        this.embeddingClient = embeddingClient;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
    }

    /** 启动时从本地索引文件加载，避免无谓重新 Embedding。 */
    @PostConstruct
    void loadFromDisk() {
        Path indexFile = indexFile();
        if (!Files.isRegularFile(indexFile)) {
            log.info("Local vector index empty, will create on first upsert: {}", indexFile.toAbsolutePath());
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(indexFile.toFile());
            JsonNode points = root.path("points");
            if (!points.isArray()) {
                return;
            }
            int loaded = 0;
            for (JsonNode point : points) {
                VectorRecord record = deserializePoint(point);
                if (record != null) {
                    memoryIndex.put(record.vectorId(), record);
                    loaded++;
                }
            }
            log.info("Local vector index loaded: {} points from {}", loaded, indexFile.toAbsolutePath());
        } catch (Exception ex) {
            log.warn("Local vector index load failed, starting empty: {}", ex.getMessage());
            memoryIndex.clear();
        }
    }

    /**
     * 将单个切块 embedding 后写入内存索引并落盘。
     * 若同 {@code vectorId} 且正文未变，复用已有向量（跳过 Embedding API）。
     */
    public void upsertChunk(DomainModels.DocumentChunk chunk, DomainModels.KnowledgeDocument document) {
        upsertChunk(chunk, document, true);
    }

    /**
     * 对文档全部切块执行 upsert，整篇只落盘一次。
     */
    public void upsertDocument(DomainModels.KnowledgeDocument document) {
        for (DomainModels.DocumentChunk chunk : document.chunks()) {
            upsertChunk(chunk, document, false);
        }
        persistToDisk();
    }

    private void upsertChunk(
            DomainModels.DocumentChunk chunk,
            DomainModels.KnowledgeDocument document,
            boolean persist
    ) {
        VectorRecord existing = memoryIndex.get(chunk.vectorId());
        float[] vector;
        if (existing != null
                && Objects.equals(existing.content(), chunk.content())
                && existing.vector() != null
                && existing.vector().length > 0) {
            vector = existing.vector();
        } else {
            vector = embeddingClient.embed(chunk.content());
        }
        VectorRecord record = new VectorRecord(
                chunk.vectorId(),
                chunk.kbId(),
                chunk.documentId(),
                chunk.id(),
                chunk.chunkIndex(),
                chunk.sectionTitle(),
                document.fileName(),
                document.serviceCode(),
                document.priority(),
                chunk.content(),
                chunk,
                document,
                vector
        );
        memoryIndex.put(record.vectorId(), record);
        if (persist) {
            persistToDisk();
        }
    }

    /**
     * 按文档 ID 删除内存点并同步落盘。
     */
    public void deleteByDocumentId(Long documentId) {
        List<String> vectorIds = memoryIndex.values().stream()
                .filter(record -> documentId.equals(record.documentId()))
                .map(VectorRecord::vectorId)
                .toList();
        for (String vectorId : vectorIds) {
            memoryIndex.remove(vectorId);
        }
        if (!vectorIds.isEmpty()) {
            persistToDisk();
        }
    }

    /**
     * 检索：精确余弦 + metadata 过滤；policy 额外 +0.05（召回偏置，不替代证据治理预算）。
     */
    public List<ScoredChunk> search(
            Long kbId,
            String query,
            int topK,
            Set<String> serviceCodes
    ) {
        float[] queryVector = embeddingClient.embed(query);
        List<ScoredChunk> scored = new ArrayList<>();

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

        scored.sort(Comparator.comparing(ScoredChunk::score).reversed());
        if (scored.size() <= topK) {
            return scored;
        }
        return scored.subList(0, topK);
    }

    /** 将当前索引原子写入本地文件（先写 .tmp 再 move，降低崩溃半写风险）。 */
    private void persistToDisk() {
        synchronized (persistLock) {
            try {
                Path dir = indexDir();
                Files.createDirectories(dir);
                Path indexFile = indexFile();
                Path tempFile = dir.resolve("index.json.tmp");

                ObjectNode root = objectMapper.createObjectNode();
                root.put("engine", "faiss-local-flat-ip");
                root.put("distance", "cosine");
                root.put("updatedAt", OffsetDateTime.now().toString());
                root.put("count", memoryIndex.size());
                ArrayNode points = root.putArray("points");
                for (VectorRecord record : memoryIndex.values()) {
                    points.add(serializePoint(record));
                }

                objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), root);
                try {
                    Files.move(tempFile, indexFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception moveEx) {
                    Files.move(tempFile, indexFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception ex) {
                log.warn("Local vector index persist failed: {}", ex.getMessage());
            }
        }
    }

    private ObjectNode serializePoint(VectorRecord record) {
        ObjectNode point = objectMapper.createObjectNode();
        point.put("vectorId", record.vectorId());
        point.put("kbId", record.kbId());
        point.put("documentId", record.documentId());
        if (record.chunkId() != null) {
            point.put("chunkId", record.chunkId());
        }
        if (record.chunkIndex() != null) {
            point.put("chunkIndex", record.chunkIndex());
        }
        point.put("sectionTitle", record.sectionTitle() == null ? "" : record.sectionTitle());
        point.put("documentName", record.documentName() == null ? "" : record.documentName());
        point.put("serviceCode", record.serviceCode() == null ? "" : record.serviceCode());
        point.put("priority", record.priority() == null ? "" : record.priority());
        point.put("content", record.content() == null ? "" : record.content());
        ArrayNode vectorNode = point.putArray("vector");
        for (float value : record.vector()) {
            vectorNode.add(value);
        }
        return point;
    }

    private VectorRecord deserializePoint(JsonNode point) {
        String vectorId = point.path("vectorId").asText(null);
        if (!StringUtils.hasText(vectorId)) {
            return null;
        }
        JsonNode vectorNode = point.path("vector");
        if (!vectorNode.isArray() || vectorNode.isEmpty()) {
            return null;
        }
        float[] vector = new float[vectorNode.size()];
        for (int i = 0; i < vectorNode.size(); i++) {
            vector[i] = (float) vectorNode.get(i).asDouble();
        }

        Long kbId = point.path("kbId").isMissingNode() ? null : point.path("kbId").asLong();
        Long documentId = point.path("documentId").isMissingNode() ? null : point.path("documentId").asLong();
        Long chunkId = point.path("chunkId").isMissingNode() || point.path("chunkId").isNull()
                ? null : point.path("chunkId").asLong();
        Integer chunkIndex = point.path("chunkIndex").isMissingNode() || point.path("chunkIndex").isNull()
                ? null : point.path("chunkIndex").asInt();
        String sectionTitle = point.path("sectionTitle").asText("");
        String documentName = point.path("documentName").asText("");
        String serviceCode = point.path("serviceCode").asText("");
        String priority = point.path("priority").asText("");
        String content = point.path("content").asText("");

        DomainModels.DocumentChunk chunk = new DomainModels.DocumentChunk(
                chunkId,
                documentId,
                kbId,
                vectorId,
                chunkIndex,
                sectionTitle,
                priority,
                content,
                Map.of()
        );
        DomainModels.KnowledgeDocument document = new DomainModels.KnowledgeDocument(
                documentId,
                kbId,
                documentName,
                "",
                "",
                "",
                "ready",
                priority,
                serviceCode,
                List.of(chunk),
                OffsetDateTime.now()
        );
        return new VectorRecord(
                vectorId,
                kbId,
                documentId,
                chunkId,
                chunkIndex,
                sectionTitle,
                documentName,
                serviceCode,
                priority,
                content,
                chunk,
                document,
                vector
        );
    }

    private Path indexDir() {
        String configured = aiProperties.getFaissIndexDir();
        String path = StringUtils.hasText(configured) ? configured : "data/faiss-index";
        return Path.of(path);
    }

    private Path indexFile() {
        return indexDir().resolve("index.json");
    }

    /** 计算两向量余弦相似度（等价 Faiss 内积于 L2 归一化向量）。 */
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

    /** 索引记录：向量 + metadata + 领域对象引用。 */
    private record VectorRecord(
            String vectorId,
            Long kbId,
            Long documentId,
            Long chunkId,
            Integer chunkIndex,
            String sectionTitle,
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
