package com.company.aics.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 领域模型容器：用户、会话、消息、知识库、Agent 规划等不可变 record/枚举，与持久化实体解耦。
 * 应用层与 API 映射均围绕本文件中的类型展开。
 */
public final class DomainModels {

    /**
     * 工具类禁止实例化。
     */
    private DomainModels() {
    }

    /** 消息角色：用户或助手。 */
    public enum MessageRole {
        USER,
        ASSISTANT
    }

    /** 系统用户。 */
    public record User(
            Long id,
            String email,
            String phone,
            String passwordHash,
            String displayName,
            Integer status,
            OffsetDateTime createdAt
    ) {
    }

    /** 客服会话。 */
    public record Conversation(
            Long id,
            Long userId,
            Long kbId,
            String title,
            String lastIntent,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    /** 回答引用的知识片段。 */
    public record Citation(
            Long documentId,
            String documentName,
            String chunkId,
            String snippet
    ) {
    }

    /** 会话中的一条消息（含检索与延迟等观测字段）。 */
    public record Message(
            Long id,
            Long conversationId,
            Long userId,
            MessageRole role,
            String content,
            List<Citation> citations,
            String intentLabel,
            String answerStatus,
            Integer retrievalCount,
            Double topScore,
            Integer latencyMs,
            String traceId,
            OffsetDateTime createdAt
    ) {
    }

    /** 对助手消息的反馈。 */
    public record MessageFeedback(
            Long id,
            Long messageId,
            Long userId,
            Integer rating,
            String reasonCode,
            String comment,
            OffsetDateTime createdAt
    ) {
    }

    /** 知识库元数据。 */
    public record KnowledgeBase(
            Long id,
            String name,
            String kbType,
            String description,
            OffsetDateTime createdAt
    ) {
    }

    /** 文档切块，含向量 ID 与元数据。 */
    public record DocumentChunk(
            Long id,
            Long documentId,
            Long kbId,
            String vectorId,
            Integer chunkIndex,
            String sectionTitle,
            String priority,
            String content,
            Map<String, Object> metadata
    ) {
    }

    /** 已入库的知识文档及其切块列表。 */
    public record KnowledgeDocument(
            Long id,
            Long kbId,
            String fileName,
            String fileExt,
            String docType,
            String contentHash,
            String status,
            String priority,
            String serviceCode,
            List<DocumentChunk> chunks,
            OffsetDateTime uploadedAt
    ) {
    }

    /** 微服务目录条目。 */
    public record ServiceCatalogItem(
            Long id,
            String serviceCode,
            String serviceName,
            String serviceType,
            String ownerTeam,
            String description
    ) {
    }

    /** 服务间依赖关系。 */
    public record ServiceDependency(
            Long id,
            String fromServiceCode,
            String toServiceCode,
            String dependencyType,
            String dependencyDesc
    ) {
    }

    /** Agent 规划中受影响的服务及理由。 */
    public record ImpactedService(
            String serviceCode,
            String serviceName,
            String reason
    ) {
    }

    /** 技术文档命中证据（可追溯为何改这些服务）。 */
    public record AgentEvidenceHit(
            String fileName,
            String serviceCode,
            double score
    ) {
    }

    /** Agent 拆解出的实施任务。 */
    public record AgentTask(
            Long taskId,
            String taskName,
            String targetService,
            String executionMode,
            List<Long> dependsOn,
            String reason,
            /** 负责团队（来自服务目录，可空）。 */
            String ownerTeam,
            /** 主要上游依赖类型 event/data/api/config（可空）。 */
            String dependencyType
    ) {
    }

    /** 一次需求拆解生成的完整规划（含生产向变更单与评审字段）。 */
    public record AgentPlan(
            Long id,
            Long userId,
            String requirementTitle,
            String requirementContent,
            String status,
            List<ImpactedService> impactedServices,
            List<List<String>> parallelGroups,
            List<AgentTask> tasks,
            List<String> validationSteps,
            List<String> missingEvidence,
            OffsetDateTime createdAt,
            /** 变更单号（可选）。 */
            String changeTicketId,
            /** 优先级 P0/P1/P2（可选）。 */
            String priority,
            /** 提出人（可选）。 */
            String requester,
            /** 本计划用到的文档命中。 */
            List<AgentEvidenceHit> evidenceHits,
            /** 本计划实际用到的依赖边。 */
            List<ServiceDependency> dependencyEdgesUsed,
            /** 建议合并/发布顺序（服务码拓扑序）。 */
            List<String> suggestedReleaseOrder,
            /** 人工评审清单。 */
            List<String> reviewChecklist
    ) {
    }
}
