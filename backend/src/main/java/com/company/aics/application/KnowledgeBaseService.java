package com.company.aics.application;

import com.company.aics.config.AiProperties;
import com.company.aics.domain.DomainModels;
import com.company.aics.persistence.AppDataStore;
import com.company.aics.rag.VectorIndexService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库应用服务：知识库 CRUD、文档上传切块、向量索引同步，以及客服/技术文档检索。
 * 向量检索优先走 {@link VectorIndexService}；无命中时回退到关键词启发式打分。
 * 元数据与切块持久化依赖 MySQL 门面 {@link AppDataStore}。
 * 上传先落库为 processing，异步向量化后变为 ready / failed。
 */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("txt", "md", "pdf");

    private final AppDataStore appDataStore;
    private final VectorIndexService vectorIndexService;
    private final AiProperties aiProperties;
    private final TaskExecutor documentIngestExecutor;

    /**
     * @param appDataStore           MySQL 数据访问门面
     * @param vectorIndexService     向量索引服务
     * @param aiProperties           RAG/默认知识库配置
     * @param documentIngestExecutor 文档异步入库执行器
     */
    public KnowledgeBaseService(
            AppDataStore appDataStore,
            VectorIndexService vectorIndexService,
            AiProperties aiProperties,
            @Qualifier("documentIngestExecutor") TaskExecutor documentIngestExecutor
    ) {
        this.appDataStore = appDataStore;
        this.vectorIndexService = vectorIndexService;
        this.aiProperties = aiProperties;
        this.documentIngestExecutor = documentIngestExecutor;
    }

    /**
     * 创建知识库元数据并写入 MySQL。
     */
    public DomainModels.KnowledgeBase createKnowledgeBase(String name, String kbType, String description) {
        return appDataStore.saveKnowledgeBase(new DomainModels.KnowledgeBase(
                null,
                name,
                kbType,
                description,
                now()
        ));
    }

    /**
     * 列出全部知识库（按创建时间倒序）。
     */
    public List<DomainModels.KnowledgeBase> listKnowledgeBases() {
        return appDataStore.listKnowledgeBases();
    }

    /**
     * 上传文档：解析 → 落库（status=processing）→ 异步向量化 → ready/failed。
     * 接口立即返回 processing，前端可轮询观察状态流转。
     */
    public DomainModels.KnowledgeDocument uploadDocument(Long kbId, MultipartFile file, String priority) {
        DomainModels.KnowledgeBase knowledgeBase = requireKnowledgeBase(kbId);
        String originalFilename = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "uploaded.txt";
        String extension = fileExtension(originalFilename).toLowerCase();
        validateExtension(extension);

        final String content;
        try {
            content = extractDocumentContent(file, extension);
        } catch (RuntimeException ex) {
            DomainModels.KnowledgeDocument failed = appDataStore.saveDocument(new DomainModels.KnowledgeDocument(
                    null,
                    kbId,
                    originalFilename,
                    extension,
                    "technical_docs".equals(knowledgeBase.kbType()) ? "service_spec" : "manual",
                    "parse-failed",
                    "failed",
                    StringUtils.hasText(priority) ? priority : "general",
                    null,
                    List.of(),
                    now()
            ));
            log.warn("文档解析失败 documentId={} file={}", failed.id(), originalFilename, ex);
            return failed;
        }

        List<String> chunks = splitText(content, 520, 80);
        if (chunks.isEmpty()) {
            chunks = List.of("该文档没有可解析的文本内容。");
        }

        String serviceCode = "technical_docs".equals(knowledgeBase.kbType()) ? inferServiceCode(originalFilename, content) : null;
        String resolvedPriority = StringUtils.hasText(priority) ? priority : "general";
        String docType = "technical_docs".equals(knowledgeBase.kbType()) ? "service_spec" : "manual";
        String hash = contentHash(content);

        DomainModels.KnowledgeDocument placeholder = appDataStore.saveDocument(new DomainModels.KnowledgeDocument(
                null,
                kbId,
                originalFilename,
                extension,
                docType,
                hash,
                "processing",
                resolvedPriority,
                serviceCode,
                List.of(),
                now()
        ));

        List<DomainModels.DocumentChunk> documentChunks = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            String chunkContent = chunks.get(index);
            documentChunks.add(new DomainModels.DocumentChunk(
                    null,
                    placeholder.id(),
                    kbId,
                    "doc" + placeholder.id() + "-chunk" + (index + 1),
                    index + 1,
                    index == 0 ? "正文" : "续段",
                    resolvedPriority,
                    chunkContent,
                    Map.of(
                            "document_name", originalFilename,
                            "priority", resolvedPriority,
                            "chunk_index", index + 1,
                            "service_code", serviceCode == null ? "" : serviceCode
                    )
            ));
        }

        DomainModels.KnowledgeDocument saved = appDataStore.saveDocument(new DomainModels.KnowledgeDocument(
                placeholder.id(),
                placeholder.kbId(),
                placeholder.fileName(),
                placeholder.fileExt(),
                placeholder.docType(),
                placeholder.contentHash(),
                "processing",
                placeholder.priority(),
                placeholder.serviceCode(),
                documentChunks,
                placeholder.uploadedAt()
        ));

        Long documentId = saved.id();
        documentIngestExecutor.execute(() -> ingestDocumentAsync(documentId));
        return saved;
    }

    /**
     * 异步向量化：成功 → ready；失败 → 清理向量并标记 failed。
     */
    private void ingestDocumentAsync(Long documentId) {
        try {
            DomainModels.KnowledgeDocument document = appDataStore.findDocument(documentId)
                    .orElseThrow(() -> new IllegalStateException("文档不存在: " + documentId));
            vectorIndexService.upsertDocument(document);
            appDataStore.updateDocumentStatus(documentId, "ready");
            log.info("文档向量化完成 documentId={}", documentId);
        } catch (Exception ex) {
            log.warn("文档向量化失败 documentId={}", documentId, ex);
            try {
                vectorIndexService.deleteByDocumentId(documentId);
            } catch (Exception cleanupEx) {
                log.debug("清理失败文档向量时忽略: {}", cleanupEx.getMessage());
            }
            try {
                appDataStore.updateDocumentStatus(documentId, "failed");
            } catch (Exception statusEx) {
                log.error("更新文档失败状态出错 documentId={}", documentId, statusEx);
            }
        }
    }

    /**
     * 列出指定知识库下的文档。
     */
    public List<DomainModels.KnowledgeDocument> listDocuments(Long kbId) {
        requireKnowledgeBase(kbId);
        return appDataStore.listDocumentsByKb(kbId);
    }

    /**
     * 删除文档：先删向量点，再移除 MySQL 记录。
     */
    public void deleteDocument(Long kbId, Long documentId) {
        requireKnowledgeBase(kbId);
        DomainModels.KnowledgeDocument document = appDataStore.findDocument(documentId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在。"));
        if (!Objects.equals(document.kbId(), kbId)) {
            throw new IllegalArgumentException("文档不存在。");
        }
        vectorIndexService.deleteByDocumentId(documentId);
        appDataStore.deleteDocument(documentId);
    }

    /**
     * 多知识库自动路由：在客服类知识库（无则全部库）中探测检索，返回最高分知识库 ID。
     * 均无命中时回退到配置的默认客服知识库。
     */
    public Long resolveBestKnowledgeBaseId(String question, int topK) {
        List<DomainModels.KnowledgeBase> all = appDataStore.listKnowledgeBases();
        List<DomainModels.KnowledgeBase> candidates = all.stream()
                .filter(kb -> !"technical_docs".equals(kb.kbType()))
                .toList();
        if (candidates.isEmpty()) {
            candidates = all;
        }

        Long bestId = aiProperties.getDefaultSupportKbId();
        double bestScore = -1.0;
        int probeK = Math.max(1, Math.min(3, topK));
        for (DomainModels.KnowledgeBase kb : candidates) {
            List<SearchHit> hits = searchSupportChunks(kb.id(), question, probeK);
            if (!hits.isEmpty() && hits.getFirst().score() > bestScore) {
                bestScore = hits.getFirst().score();
                bestId = kb.id();
            }
        }

        if (bestId != null && appDataStore.knowledgeBaseExists(bestId)) {
            return bestId;
        }
        if (!candidates.isEmpty()) {
            return candidates.getFirst().id();
        }
        return aiProperties.getDefaultSupportKbId();
    }

    /**
     * 客服知识检索：优先向量搜索，无结果时关键词回退打分。
     * 关键词回退仅使用 status=ready 的文档，避免处理中/失败文档参与检索。
     */
    public List<SearchHit> searchSupportChunks(Long kbId, String question, int topK) {
        Long resolvedKbId = kbId == null ? aiProperties.getDefaultSupportKbId() : kbId;
        List<VectorIndexService.ScoredChunk> vectorHits = vectorIndexService.search(resolvedKbId, question, topK, Set.of());
        if (!vectorHits.isEmpty()) {
            return vectorHits.stream()
                    .map(hit -> new SearchHit(hit.document(), hit.chunk(), hit.score()))
                    .toList();
        }

        Set<String> keywords = extractKeywords(question);
        List<SearchHit> hits = new ArrayList<>();
        for (DomainModels.KnowledgeDocument document : listDocuments(resolvedKbId)) {
            if (!"ready".equalsIgnoreCase(document.status())) {
                continue;
            }
            for (DomainModels.DocumentChunk chunk : document.chunks()) {
                double score = score(question, chunk.content(), keywords, document);
                if (score > 0) {
                    hits.add(new SearchHit(document, chunk, Math.min(1.0, score / 3.0)));
                }
            }
        }
        hits.sort(Comparator.comparing(SearchHit::score).reversed());
        return hits.stream().limit(topK).toList();
    }

    /**
     * 技术文档检索：使用配置的技术库 ID（缺失时回退到 kbType=technical_docs）。
     * 可按服务码过滤范围。
     */
    public List<SearchHit> searchTechnicalDocuments(String requirement, Set<String> scopedServiceCodes, int topK) {
        Long techKbId = resolveTechnicalKbId();
        Set<String> scope = scopedServiceCodes == null ? Set.of() : scopedServiceCodes;
        List<VectorIndexService.ScoredChunk> vectorHits = vectorIndexService.search(techKbId, requirement, topK, scope);
        if (!vectorHits.isEmpty()) {
            return vectorHits.stream()
                    .map(hit -> new SearchHit(hit.document(), hit.chunk(), hit.score()))
                    .toList();
        }

        Set<String> keywords = extractKeywords(requirement);
        List<SearchHit> hits = new ArrayList<>();
        for (DomainModels.KnowledgeDocument document : listDocuments(techKbId)) {
            if (!scope.isEmpty() && !scope.contains(document.serviceCode())) {
                continue;
            }
            for (DomainModels.DocumentChunk chunk : document.chunks()) {
                double score = score(requirement, chunk.content(), keywords, document);
                if (score > 0) {
                    hits.add(new SearchHit(document, chunk, Math.min(1.0, score / 3.0)));
                }
            }
        }
        hits.sort(Comparator.comparing(SearchHit::score).reversed());
        return hits.stream().limit(topK).toList();
    }

    /** 解析技术文档库 ID：优先配置，其次按类型匹配。 */
    private Long resolveTechnicalKbId() {
        Long configured = aiProperties.getDefaultTechnicalKbId();
        if (configured != null && appDataStore.findKnowledgeBase(configured).isPresent()) {
            return configured;
        }
        return appDataStore.listKnowledgeBases().stream()
                .filter(kb -> "technical_docs".equalsIgnoreCase(kb.kbType()))
                .map(DomainModels.KnowledgeBase::id)
                .findFirst()
                .orElse(configured == null ? 2L : configured);
    }

    /**
     * 构建 serviceCode → 目录项索引，供 Agent 规划使用。
     */
    public Map<String, DomainModels.ServiceCatalogItem> serviceCatalogIndex() {
        Map<String, DomainModels.ServiceCatalogItem> result = new LinkedHashMap<>();
        for (DomainModels.ServiceCatalogItem item : appDataStore.listServiceCatalog()) {
            result.put(item.serviceCode(), item);
        }
        return result;
    }

    /** 校验知识库存在。 */
    private DomainModels.KnowledgeBase requireKnowledgeBase(Long kbId) {
        return appDataStore.findKnowledgeBase(kbId)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在。"));
    }

    /** 校验文件扩展名是否在白名单内。 */
    private void validateExtension(String extension) {
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("仅支持 .txt、.md、.pdf 文件。");
        }
    }

    /**
     * 按扩展名解析上传文件正文。
     */
    private String extractDocumentContent(MultipartFile file, String extension) {
        try {
            return switch (extension) {
                case "txt" -> normalizePlainText(new String(file.getBytes(), StandardCharsets.UTF_8));
                case "md" -> normalizeMarkdown(new String(file.getBytes(), StandardCharsets.UTF_8));
                case "pdf" -> normalizePlainText(readPdf(file.getInputStream()));
                default -> throw new IllegalArgumentException("不支持的文件类型。");
            };
        } catch (IOException ex) {
            throw new IllegalArgumentException("读取上传文件失败。");
        }
    }

    /** 使用 PDFBox 抽取 PDF 纯文本。 */
    private String readPdf(InputStream inputStream) throws IOException {
        try (PDDocument document = Loader.loadPDF(inputStream.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    /** 规范化纯文本换行；空内容时返回占位说明。 */
    private String normalizePlainText(String content) {
        String normalized = content.replace("\r\n", "\n").trim();
        return StringUtils.hasText(normalized) ? normalized : "该文档没有可解析的文本内容。";
    }

    /** 去掉常见 Markdown 标记后再规范化。 */
    private String normalizeMarkdown(String content) {
        String normalized = content
                .replace("\r\n", "\n")
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("(?m)^>\\s*", "")
                .replaceAll("(?m)^[-*+]\\s+", "")
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .trim();
        return StringUtils.hasText(normalized) ? normalized : "该文档没有可解析的文本内容。";
    }

    /**
     * 按段落聚合切块，超长块再按窗口滑动切分，保留 overlap 字符重叠。
     */
    private List<String> splitText(String text, int chunkSize, int overlap) {
        List<String> paragraphs = new ArrayList<>();
        for (String paragraph : text.split("\\n{2,}")) {
            String cleaned = paragraph.trim();
            if (StringUtils.hasText(cleaned)) {
                paragraphs.add(cleaned);
            }
        }

        if (paragraphs.isEmpty()) {
            paragraphs = List.of(text.trim());
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String paragraph : paragraphs) {
            if (current.length() == 0) {
                current.append(paragraph);
                continue;
            }

            if (current.length() + 2 + paragraph.length() <= chunkSize) {
                current.append("\n\n").append(paragraph);
                continue;
            }

            chunks.add(current.toString().trim());
            // 保留尾部 overlap，降低切块边界丢语义的风险
            String carry = current.length() > overlap
                    ? current.substring(Math.max(0, current.length() - overlap))
                    : current.toString();
            current = new StringBuilder(carry).append("\n\n").append(paragraph);
        }

        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }

        // 对仍超长的块做固定窗口二次切分
        List<String> normalizedChunks = new ArrayList<>();
        for (String chunk : chunks) {
            if (chunk.length() <= chunkSize) {
                normalizedChunks.add(chunk);
                continue;
            }

            int start = 0;
            while (start < chunk.length()) {
                int end = Math.min(chunk.length(), start + chunkSize);
                normalizedChunks.add(chunk.substring(start, end).trim());
                if (end >= chunk.length()) {
                    break;
                }
                start = Math.max(0, end - overlap);
            }
        }
        return normalizedChunks;
    }

    /**
     * 关键词回退打分：正文/文件名/服务码命中加权，并对退货退款等主题加分。
     */
    private double score(
            String query,
            String content,
            Set<String> keywords,
            DomainModels.KnowledgeDocument document
    ) {
        String loweredQuery = query.toLowerCase();
        String loweredContent = content.toLowerCase();
        String loweredFileName = document.fileName().toLowerCase();

        double score = 0.0;
        for (String keyword : keywords) {
            String loweredKeyword = keyword.toLowerCase();
            if (loweredContent.contains(loweredKeyword)) {
                score += 0.65;
            }
            if (loweredFileName.contains(loweredKeyword)) {
                score += 0.25;
            }
            if (StringUtils.hasText(document.serviceCode()) && document.serviceCode().toLowerCase().contains(loweredKeyword)) {
                score += 0.35;
            }
        }

        if ("policy".equals(document.docType()) || "policy".equals(document.priority())) {
            score += 0.15;
        }

        // 主题对齐加分：查询与正文同现关键业务词时抬高分数
        if ((query.contains("退货") || loweredQuery.contains("return"))
                && (loweredContent.contains("退货") || loweredContent.contains("return"))) {
            score += 0.4;
        }
        if ((query.contains("退款") || loweredQuery.contains("refund"))
                && (loweredContent.contains("退款") || loweredContent.contains("refund"))) {
            score += 0.4;
        }
        if ((query.contains("订单") || loweredQuery.contains("order"))
                && (loweredContent.contains("订单") || loweredContent.contains("order"))) {
            score += 0.4;
        }
        if ((query.contains("短信") || loweredQuery.contains("sms"))
                && (loweredContent.contains("短信") || loweredContent.contains("sms"))) {
            score += 0.4;
        }
        if ((query.contains("通知") || loweredQuery.contains("notification"))
                && (loweredContent.contains("通知") || loweredContent.contains("notification"))) {
            score += 0.25;
        }
        return score;
    }

    /**
     * 从查询文本抽取业务关键词组；无命中时退化为分词片段。
     */
    private Set<String> extractKeywords(String text) {
        String lowered = text.toLowerCase();
        Set<String> keywords = new LinkedHashSet<>();

        addKeywordGroup(keywords, text, lowered, List.of("退货", "return", "returns"));
        addKeywordGroup(keywords, text, lowered, List.of("退款", "refund", "refunds"));
        addKeywordGroup(keywords, text, lowered, List.of("订单", "order", "orders"));
        addKeywordGroup(keywords, text, lowered, List.of("短信", "sms", "text message"));
        addKeywordGroup(keywords, text, lowered, List.of("通知", "notification", "notify"));
        addKeywordGroup(keywords, text, lowered, List.of("手机号", "phone", "mobile"));
        addKeywordGroup(keywords, text, lowered, List.of("用户", "user", "users"));
        addKeywordGroup(keywords, text, lowered, List.of("前端", "frontend", "web"));
        addKeywordGroup(keywords, text, lowered, List.of("页面", "page", "screen"));
        addKeywordGroup(keywords, text, lowered, List.of("物流", "shipping", "delivery"));
        addKeywordGroup(keywords, text, lowered, List.of("发货", "ship", "shipment"));
        addKeywordGroup(keywords, text, lowered, List.of("成功", "success", "successful"));
        addKeywordGroup(keywords, text, lowered, List.of("event", "api", "service"));

        if (keywords.isEmpty()) {
            for (String token : text.split("[\\s,，。！？/]+")) {
                if (token.length() > 1) {
                    keywords.add(token);
                }
            }
        }
        return keywords;
    }

    /**
     * 若原文命中同义词组中任一词，则整组同义词加入关键词集合。
     */
    private void addKeywordGroup(Set<String> keywords, String original, String lowered, List<String> values) {
        for (String value : values) {
            if (original.contains(value) || lowered.contains(value.toLowerCase())) {
                keywords.addAll(values);
                return;
            }
        }
    }

    /**
     * 根据文件名与正文启发式推断微服务编码。
     */
    private String inferServiceCode(String fileName, String content) {
        String joined = (fileName + "\n" + content).toLowerCase();
        if (joined.contains("order") || joined.contains("订单")) {
            return "order-service";
        }
        if (joined.contains("notification") || joined.contains("sms") || joined.contains("通知") || joined.contains("短信")) {
            return "notification-service";
        }
        if (joined.contains("user") || joined.contains("phone") || joined.contains("用户") || joined.contains("手机号")) {
            return "user-service";
        }
        if (joined.contains("mall") || joined.contains("web") || joined.contains("frontend") || joined.contains("前端")) {
            return "mall-web";
        }
        return "unknown-service";
    }

    /** 计算文档正文 SHA-256 十六进制摘要。 */
    private String contentHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("无法计算内容哈希。", ex);
        }
    }

    /** 从文件名提取扩展名（不含点）。 */
    private String fileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot < 0 ? "" : fileName.substring(lastDot + 1);
    }

    /** @return 东八区当前时间 */
    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.ofHours(8));
    }

    /** 检索命中：文档、切块与相似度分数。 */
    public record SearchHit(
            DomainModels.KnowledgeDocument document,
            DomainModels.DocumentChunk chunk,
            double score
    ) {
    }
}
