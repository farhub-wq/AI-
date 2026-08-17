package com.company.aics.api;

import com.company.aics.application.ConversationService;
import com.company.aics.domain.DomainModels;
import java.util.List;

/**
 * 领域模型到 API View 的静态映射工具，集中处理字段裁剪与命名转换。
 * 禁止实例化，全部为无状态静态方法。
 */
public final class ApiMappers {

    /**
     * 工具类禁止实例化。
     */
    private ApiMappers() {
    }

    /**
     * 用户领域对象 → 对外用户视图（不含密码哈希）。
     */
    public static ApiModels.UserView toUserView(DomainModels.User user) {
        return new ApiModels.UserView(user.id(), user.displayName(), user.email(), user.phone(), user.role());
    }

    /**
     * 会话摘要视图：末条消息截断为预览文案。
     */
    public static ApiModels.ConversationSummaryView toConversationSummaryView(
            DomainModels.Conversation conversation,
            List<DomainModels.Message> messages
    ) {
        String preview = messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
        // 列表预览仅保留前 32 字，避免过长内容撑爆 UI
        if (preview.length() > 32) {
            preview = preview.substring(0, 32);
        }
        return new ApiModels.ConversationSummaryView(
                conversation.id(),
                conversation.title(),
                conversation.kbId(),
                conversation.lastIntent(),
                preview,
                conversation.updatedAt()
        );
    }

    /**
     * 会话详情（含消息列表）→ 详情视图。
     */
    public static ApiModels.ConversationDetailView toConversationDetailView(ConversationService.ConversationDetail detail) {
        return new ApiModels.ConversationDetailView(
                detail.conversation().id(),
                detail.conversation().title(),
                detail.conversation().kbId(),
                detail.messages().stream().map(ApiMappers::toMessageView).toList()
        );
    }

    /**
     * 消息领域对象 → 消息视图；角色名转为小写字符串。
     */
    public static ApiModels.MessageView toMessageView(DomainModels.Message message) {
        return new ApiModels.MessageView(
                message.id(),
                message.role().name().toLowerCase(),
                message.content(),
                message.citations().stream().map(ApiMappers::toCitationView).toList(),
                message.intentLabel(),
                message.answerStatus(),
                message.retrievalCount(),
                message.topScore(),
                message.latencyMs(),
                message.createdAt()
        );
    }

    /**
     * 引用片段 → 引用视图。
     */
    public static ApiModels.CitationView toCitationView(DomainModels.Citation citation) {
        return new ApiModels.CitationView(
                citation.documentId(),
                citation.documentName(),
                citation.chunkId(),
                citation.snippet()
        );
    }

    /**
     * 知识库 → 知识库视图。
     */
    public static ApiModels.KnowledgeBaseView toKnowledgeBaseView(DomainModels.KnowledgeBase knowledgeBase) {
        return new ApiModels.KnowledgeBaseView(
                knowledgeBase.id(),
                knowledgeBase.name(),
                knowledgeBase.kbType(),
                knowledgeBase.description(),
                knowledgeBase.createdAt()
        );
    }

    /**
     * 知识文档 → 文档视图（含切块数量）。
     */
    public static ApiModels.KnowledgeDocumentView toKnowledgeDocumentView(DomainModels.KnowledgeDocument document) {
        return new ApiModels.KnowledgeDocumentView(
                document.id(),
                document.kbId(),
                document.fileName(),
                document.fileExt(),
                document.docType(),
                document.status(),
                document.priority(),
                document.serviceCode(),
                document.chunks().size(),
                document.uploadedAt()
        );
    }

    /**
     * 消息反馈 → 反馈响应。
     */
    public static ApiModels.FeedbackResponse toFeedbackResponse(DomainModels.MessageFeedback feedback) {
        return new ApiModels.FeedbackResponse(
                feedback.messageId(),
                feedback.rating(),
                feedback.reasonCode(),
                feedback.comment(),
                feedback.createdAt()
        );
    }

    /**
     * 受影响服务 → 视图。
     */
    public static ApiModels.ImpactedServiceView toImpactedServiceView(DomainModels.ImpactedService impactedService) {
        return new ApiModels.ImpactedServiceView(
                impactedService.serviceCode(),
                impactedService.serviceName(),
                impactedService.reason()
        );
    }

    /** 文档命中证据 → 视图。 */
    public static ApiModels.AgentEvidenceHitView toAgentEvidenceHitView(DomainModels.AgentEvidenceHit hit) {
        return new ApiModels.AgentEvidenceHitView(hit.fileName(), hit.serviceCode(), hit.score());
    }

    /**
     * Agent 任务 → 任务视图。
     */
    public static ApiModels.AgentTaskView toAgentTaskView(DomainModels.AgentTask task) {
        return new ApiModels.AgentTaskView(
                task.taskId(),
                task.taskName(),
                task.targetService(),
                task.executionMode(),
                task.dependsOn(),
                task.reason(),
                task.ownerTeam(),
                task.dependencyType()
        );
    }

    /**
     * Agent 规划 → 详情视图。
     */
    public static ApiModels.AgentPlanDetailView toAgentPlanDetailView(DomainModels.AgentPlan plan) {
        return new ApiModels.AgentPlanDetailView(
                plan.id(),
                plan.requirementTitle(),
                plan.requirementContent(),
                plan.status(),
                plan.impactedServices().stream().map(ApiMappers::toImpactedServiceView).toList(),
                plan.parallelGroups(),
                plan.tasks().stream().map(ApiMappers::toAgentTaskView).toList(),
                plan.validationSteps(),
                plan.missingEvidence(),
                plan.createdAt(),
                plan.changeTicketId(),
                plan.priority(),
                plan.requester(),
                plan.evidenceHits() == null ? List.of() : plan.evidenceHits().stream()
                        .map(ApiMappers::toAgentEvidenceHitView).toList(),
                plan.dependencyEdgesUsed() == null ? List.of() : plan.dependencyEdgesUsed().stream()
                        .map(ApiMappers::toServiceDependencyView).toList(),
                plan.suggestedReleaseOrder() == null ? List.of() : plan.suggestedReleaseOrder(),
                plan.reviewChecklist() == null ? List.of() : plan.reviewChecklist()
        );
    }

    /**
     * Agent 规划 → 列表摘要视图。
     */
    public static ApiModels.AgentPlanSummaryView toAgentPlanSummaryView(DomainModels.AgentPlan plan) {
        return new ApiModels.AgentPlanSummaryView(
                plan.id(),
                plan.requirementTitle(),
                plan.status(),
                plan.impactedServices().size(),
                plan.createdAt()
        );
    }

    /**
     * 服务目录项 → 视图。
     */
    public static ApiModels.ServiceCatalogView toServiceCatalogView(DomainModels.ServiceCatalogItem item) {
        return new ApiModels.ServiceCatalogView(
                item.serviceCode(),
                item.serviceName(),
                item.serviceType(),
                item.ownerTeam(),
                item.description()
        );
    }

    /**
     * 服务依赖边 → 视图。
     */
    public static ApiModels.ServiceDependencyView toServiceDependencyView(DomainModels.ServiceDependency dependency) {
        return new ApiModels.ServiceDependencyView(
                dependency.fromServiceCode(),
                dependency.toServiceCode(),
                dependency.dependencyType(),
                dependency.dependencyDesc()
        );
    }
}
