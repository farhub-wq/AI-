package com.company.aics.persistence;

import com.company.aics.domain.DomainModels;
import com.company.aics.persistence.entity.AgentPlanEntity;
import com.company.aics.persistence.entity.ConversationEntity;
import com.company.aics.persistence.entity.DocumentChunkEntity;
import com.company.aics.persistence.entity.KnowledgeBaseEntity;
import com.company.aics.persistence.entity.KnowledgeDocumentEntity;
import com.company.aics.persistence.entity.MessageEntity;
import com.company.aics.persistence.entity.MessageFeedbackEntity;
import com.company.aics.persistence.entity.ServiceCatalogEntity;
import com.company.aics.persistence.entity.ServiceDependencyEntity;
import com.company.aics.persistence.entity.UserEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 持久化实体与领域模型互转工具：时间区偏移、JSON 字段读写、Agent 规划载荷编解码。
 * 解析失败时对引用/metadata/规划 JSON 返回空集合，避免脏数据拖垮读路径。
 */
@Component
public class DomainMapper {

    /** 业务统一使用东八区。 */
    private static final ZoneOffset ZONE = ZoneOffset.ofHours(8);
    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper JSON 序列化组件
     */
    public DomainMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * LocalDateTime → OffsetDateTime（东八区）。
     */
    public OffsetDateTime toOffset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZONE);
    }

    /**
     * OffsetDateTime → LocalDateTime；空值则取当前本地时间。
     */
    public LocalDateTime toLocal(OffsetDateTime value) {
        return value == null ? LocalDateTime.now() : value.withOffsetSameInstant(ZONE).toLocalDateTime();
    }

    /** 用户实体 → 领域用户。 */
    public DomainModels.User toUser(UserEntity entity) {
        return new DomainModels.User(
                entity.getId(),
                entity.getEmail(),
                entity.getPhone(),
                entity.getPasswordHash(),
                entity.getDisplayName(),
                entity.getStatus(),
                toOffset(entity.getCreatedAt())
        );
    }

    /** 会话实体 → 领域会话。 */
    public DomainModels.Conversation toConversation(ConversationEntity entity) {
        return new DomainModels.Conversation(
                entity.getId(),
                entity.getUserId(),
                entity.getKbId(),
                entity.getTitle(),
                entity.getLastIntent(),
                toOffset(entity.getCreatedAt()),
                toOffset(entity.getUpdatedAt())
        );
    }

    /** 消息实体 → 领域消息（角色字符串映射为枚举，引用 JSON 反序列化）。 */
    public DomainModels.Message toMessage(MessageEntity entity) {
        return new DomainModels.Message(
                entity.getId(),
                entity.getConversationId(),
                entity.getUserId(),
                "ASSISTANT".equalsIgnoreCase(entity.getRole())
                        ? DomainModels.MessageRole.ASSISTANT
                        : DomainModels.MessageRole.USER,
                entity.getContent(),
                readCitations(entity.getCitationsJson()),
                entity.getIntentLabel(),
                entity.getAnswerStatus(),
                entity.getRetrievalCount(),
                entity.getTopScore() == null ? 0.0 : entity.getTopScore().doubleValue(),
                entity.getLatencyMs(),
                entity.getTraceId(),
                toOffset(entity.getCreatedAt())
        );
    }

    /** 反馈实体 → 领域反馈。 */
    public DomainModels.MessageFeedback toFeedback(MessageFeedbackEntity entity) {
        return new DomainModels.MessageFeedback(
                entity.getId(),
                entity.getMessageId(),
                entity.getUserId(),
                entity.getRating(),
                entity.getReasonCode(),
                entity.getComment(),
                toOffset(entity.getCreatedAt())
        );
    }

    /** 知识库实体 → 领域知识库。 */
    public DomainModels.KnowledgeBase toKnowledgeBase(KnowledgeBaseEntity entity) {
        return new DomainModels.KnowledgeBase(
                entity.getId(),
                entity.getName(),
                entity.getKbType(),
                entity.getDescription(),
                toOffset(entity.getCreatedAt())
        );
    }

    /** 切块实体 → 领域切块。 */
    public DomainModels.DocumentChunk toChunk(DocumentChunkEntity entity) {
        return new DomainModels.DocumentChunk(
                entity.getId(),
                entity.getDocumentId(),
                entity.getKbId(),
                entity.getVectorId(),
                entity.getChunkIndex(),
                entity.getSectionTitle(),
                entity.getPriority(),
                entity.getContent(),
                readMap(entity.getMetadataJson())
        );
    }

    /** 文档实体 + 切块列表 → 领域文档。 */
    public DomainModels.KnowledgeDocument toDocument(KnowledgeDocumentEntity entity, List<DomainModels.DocumentChunk> chunks) {
        return new DomainModels.KnowledgeDocument(
                entity.getId(),
                entity.getKbId(),
                entity.getFileName(),
                entity.getFileExt(),
                entity.getDocType(),
                entity.getContentHash(),
                entity.getStatus(),
                entity.getPriority(),
                entity.getServiceCode(),
                chunks,
                toOffset(entity.getUploadedAt())
        );
    }

    /** 服务目录实体 → 领域目录项。 */
    public DomainModels.ServiceCatalogItem toServiceCatalog(ServiceCatalogEntity entity) {
        return new DomainModels.ServiceCatalogItem(
                entity.getId(),
                entity.getServiceCode(),
                entity.getServiceName(),
                entity.getServiceType(),
                entity.getOwnerTeam(),
                entity.getDescription()
        );
    }

    /** 服务依赖实体 → 领域依赖。 */
    public DomainModels.ServiceDependency toServiceDependency(ServiceDependencyEntity entity) {
        return new DomainModels.ServiceDependency(
                entity.getId(),
                entity.getFromServiceCode(),
                entity.getToServiceCode(),
                entity.getDependencyType(),
                entity.getDependencyDesc()
        );
    }

    /**
     * Agent 规划实体 → 领域规划；从 plan_json 还原任务与证据字段。
     */
    public DomainModels.AgentPlan toAgentPlan(AgentPlanEntity entity) {
        PlanPayload payload = readPlanPayload(entity.getPlanJson());
        return new DomainModels.AgentPlan(
                entity.getId(),
                entity.getUserId(),
                entity.getRequirementTitle(),
                entity.getRequirementContent(),
                entity.getStatus(),
                payload.impactedServices() == null ? List.of() : payload.impactedServices(),
                payload.parallelGroups() == null ? List.of() : payload.parallelGroups(),
                payload.tasks() == null ? List.of() : payload.tasks(),
                payload.validationSteps() == null ? List.of() : payload.validationSteps(),
                payload.missingEvidence() == null ? List.of() : payload.missingEvidence(),
                toOffset(entity.getCreatedAt())
        );
    }

    /** 序列化引用列表为 JSON 字符串。 */
    public String writeCitations(List<DomainModels.Citation> citations) {
        try {
            return objectMapper.writeValueAsString(citations == null ? List.of() : citations);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化引用失败", ex);
        }
    }

    /** 序列化切块 metadata 为 JSON。 */
    public String writeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化 metadata 失败", ex);
        }
    }

    /** 将规划中的结构化字段打包为 JSON 载荷。 */
    public String writePlanPayload(DomainModels.AgentPlan plan) {
        try {
            return objectMapper.writeValueAsString(new PlanPayload(
                    plan.impactedServices(),
                    plan.parallelGroups(),
                    plan.tasks(),
                    plan.validationSteps(),
                    plan.missingEvidence()
            ));
        } catch (Exception ex) {
            throw new IllegalStateException("序列化 Agent 规划失败", ex);
        }
    }

    /** double 分数 → BigDecimal（入库精度）。 */
    public BigDecimal toDecimal(double score) {
        return BigDecimal.valueOf(score);
    }

    /** 反序列化引用 JSON；失败返回空列表。 */
    private List<DomainModels.Citation> readCitations(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            // 脏 JSON 不阻断消息读取
            return List.of();
        }
    }

    /** 反序列化 Map 型 metadata；失败返回空 Map。 */
    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            return Collections.emptyMap();
        }
    }

    /** 反序列化规划载荷；失败返回全空载荷。 */
    private PlanPayload readPlanPayload(String json) {
        if (json == null || json.isBlank()) {
            return new PlanPayload(List.of(), List.of(), List.of(), List.of(), List.of());
        }
        try {
            return objectMapper.readValue(json, PlanPayload.class);
        } catch (Exception ex) {
            return new PlanPayload(List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    /**
     * Agent 规划 JSON 载荷：受影响服务、并行组、任务、验收步骤与缺失证据。
     */
    public record PlanPayload(
            List<DomainModels.ImpactedService> impactedServices,
            List<List<String>> parallelGroups,
            List<DomainModels.AgentTask> tasks,
            List<String> validationSteps,
            List<String> missingEvidence
    ) {
    }
}
