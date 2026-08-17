package com.company.aics.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * REST 请求/响应 DTO 容器：认证、会话、聊天、知识库、反馈、管理指标与 Agent 规划等视图模型。
 * 使用 record 表达不可变传输对象，并在请求体上挂载 Jakarta Validation 约束。
 */
public final class ApiModels {

    /**
     * 工具类禁止实例化。
     */
    private ApiModels() {
    }

    /**
     * 用户注册请求：EMAIL 与 PHONE 二选一，不可混填。
     * 邮箱注册时 email 须为常见后缀（qq/163/gmail 等）；phone 须为空。
     */
    public record RegisterRequest(
            /** 注册方式：EMAIL 或 PHONE。 */
            @NotBlank @Size(max = 16) String registerType,
            /** 邮箱（仅 EMAIL 注册填写）。 */
            String email,
            /** 手机号（仅 PHONE 注册填写，1 开头 11 位）。 */
            String phone,
            @NotBlank @Size(min = 8, max = 64) String password,
            /** 昵称（全局唯一）。 */
            @NotBlank @Size(max = 64) String displayName
    ) {
    }

    /** 注册成功响应（不签发令牌，需返回登录页自行登录）。 */
    public record RegisterResponse(
            Long userId,
            String displayName,
            /** 实际使用的注册方式 EMAIL / PHONE。 */
            String registerType,
            /** 前端展示文案，如「已注册，请返回登录页登录。」 */
            String message,
            /** true=幂等命中（账号已存在且密码正确），未新建用户。 */
            boolean alreadyRegistered
    ) {
    }

    /** 登录请求（账号为允许后缀的邮箱，或 11 位手机号）。 */
    public record LoginRequest(
            @NotBlank String account,
            @NotBlank String password
    ) {
    }

    /** 对外用户视图（不含密码哈希；邮箱/手机可为空取决于注册方式）。 */
    public record UserView(
            Long id,
            String displayName,
            /** 邮箱注册用户有值；手机注册通常为 null。 */
            String email,
            /** 手机注册用户有值；邮箱注册通常为 null。 */
            String phone,
            /** USER / ADMIN；仅 ADMIN 可访问管理后台 */
            String role
    ) {
    }

    /** 登录成功响应：短效 Access + 可轮换 Refresh。 */
    public record LoginResponse(
            String accessToken,
            String tokenType,
            long expiresIn,
            String refreshToken,
            long refreshExpiresIn,
            UserView user
    ) {
    }

    /** 刷新令牌请求：提交旧 Refresh，换取新令牌对。 */
    public record RefreshTokenRequest(
            @NotBlank String refreshToken
    ) {
    }

    /** 登出请求：携带 Refresh 以便服务端吊销。 */
    public record LogoutRequest(
            String refreshToken
    ) {
    }

    /** 创建会话请求。 */
    public record CreateConversationRequest(
            @Size(max = 255) String title,
            Long kbId
    ) {
    }

    /** 会话列表摘要视图。 */
    public record ConversationSummaryView(
            Long id,
            String title,
            Long kbId,
            String lastIntent,
            String lastMessagePreview,
            OffsetDateTime updatedAt
    ) {
    }

    /** 回答引用片段视图。 */
    public record CitationView(
            Long documentId,
            String documentName,
            String chunkId,
            String snippet
    ) {
    }

    /** 消息视图（含检索观测字段）。 */
    public record MessageView(
            Long id,
            String role,
            String content,
            List<CitationView> citations,
            String intentLabel,
            String answerStatus,
            Integer retrievalCount,
            Double topScore,
            Integer latencyMs,
            OffsetDateTime createdAt
    ) {
    }

    /** 会话详情视图（含消息列表）。 */
    public record ConversationDetailView(
            Long id,
            String title,
            Long kbId,
            List<MessageView> messages
    ) {
    }

    /** 流式问答请求。 */
    public record ChatRequest(
            Long conversationId,
            Long kbId,
            @NotBlank @Size(max = 500) String question,
            @Min(1) @Max(10) Integer historyRounds
    ) {
    }

    /** 创建知识库请求。 */
    public record CreateKnowledgeBaseRequest(
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Size(max = 64) String kbType,
            @Size(max = 500) String description
    ) {
    }

    /** 知识库视图。 */
    public record KnowledgeBaseView(
            Long id,
            String name,
            String kbType,
            String description,
            OffsetDateTime createdAt
    ) {
    }

    /** 知识文档视图。 */
    public record KnowledgeDocumentView(
            Long id,
            Long kbId,
            String fileName,
            String fileExt,
            String docType,
            String status,
            String priority,
            String serviceCode,
            Integer chunkCount,
            OffsetDateTime uploadedAt
    ) {
    }

    /** 提交消息反馈请求。 */
    public record FeedbackRequest(
            @NotNull @Min(-1) @Max(1) Integer rating,
            @NotBlank @Size(max = 64) String reasonCode,
            @Size(max = 500) String comment
    ) {
    }

    /** 反馈提交结果。 */
    public record FeedbackResponse(
            Long messageId,
            Integer rating,
            String reasonCode,
            String comment,
            OffsetDateTime createdAt
    ) {
    }

    /** 管理端总览指标。 */
    public record MetricsOverviewView(
            long dailyQuestionCount,
            long assistantMessageCount,
            long feedbackCount,
            double positiveFeedbackRate,
            double fallbackRate,
            double agentPlanSuccessRate
    ) {
    }

    /** 日提问量趋势上的单日数据点。 */
    public record DailyQuestionPointView(
            String date,
            long questionCount
    ) {
    }

    /** 低分反馈问题条目。 */
    public record FeedbackIssueView(
            Long messageId,
            Long conversationId,
            String questionPreview,
            String answerPreview,
            String reasonCode,
            String comment,
            OffsetDateTime createdAt
    ) {
    }

    /** 反馈指标汇总。 */
    public record FeedbackMetricsView(
            long positiveCount,
            long negativeCount,
            List<FeedbackIssueView> lowRatingIssues
    ) {
    }

    /** 管理端会话列表项。 */
    public record AdminConversationView(
            Long conversationId,
            String title,
            String userDisplayName,
            String kbName,
            String lastMessagePreview,
            OffsetDateTime updatedAt
    ) {
    }

    /** Agent 拆解时的可选服务范围。 */
    public record DocumentScope(
            List<String> serviceCodes
    ) {
    }

    /** Agent 需求拆解 / 变更规划请求（变更单字段可选，兼容旧客户端）。 */
    public record AgentDecomposeRequest(
            @NotBlank @Size(max = 255) String requirementTitle,
            @NotBlank @Size(max = 5000) String requirementContent,
            @Valid DocumentScope documentScope,
            /** 变更单号，如 CHG-2026-001。 */
            @Size(max = 64) String changeTicketId,
            /** 优先级：P0 / P1 / P2。 */
            @Size(max = 8) String priority,
            /** 提出人。 */
            @Size(max = 64) String requester
    ) {
    }

    /** 受影响服务视图。 */
    public record ImpactedServiceView(
            String serviceCode,
            String serviceName,
            String reason
    ) {
    }

    /** 技术文档命中证据视图。 */
    public record AgentEvidenceHitView(
            String fileName,
            String serviceCode,
            double score
    ) {
    }

    /** Agent 任务视图。 */
    public record AgentTaskView(
            Long taskId,
            String taskName,
            String targetService,
            String executionMode,
            List<Long> dependsOn,
            String reason,
            String ownerTeam,
            String dependencyType
    ) {
    }

    /** 新建规划后的摘要响应。 */
    public record AgentPlanCreateResponse(
            Long planId,
            String status,
            List<ImpactedServiceView> impactedServices,
            List<List<String>> parallelGroups,
            List<String> missingEvidence
    ) {
    }

    /** 规划详情视图（含生产向变更单与评审字段）。 */
    public record AgentPlanDetailView(
            Long planId,
            String requirementTitle,
            String requirementContent,
            String status,
            List<ImpactedServiceView> impactedServices,
            List<List<String>> parallelGroups,
            List<AgentTaskView> tasks,
            List<String> validationSteps,
            List<String> missingEvidence,
            OffsetDateTime createdAt,
            String changeTicketId,
            String priority,
            String requester,
            List<AgentEvidenceHitView> evidenceHits,
            List<ServiceDependencyView> dependencyEdgesUsed,
            List<String> suggestedReleaseOrder,
            List<String> reviewChecklist,
            /** LLM 润色摘要（可空）。 */
            String llmAssistSummary,
            /** success / skipped / failed */
            String llmAssistStatus,
            /** multi_agent_llm / rules_fallback */
            String planningMode,
            /** 多 Agent / 反思轨迹 */
            List<String> agentTrace,
            /** 反思触发重试次数 */
            Integer reflectionRetryCount
    ) {
    }

    /** 规划列表摘要视图。 */
    public record AgentPlanSummaryView(
            Long planId,
            String requirementTitle,
            String status,
            int impactedServiceCount,
            OffsetDateTime createdAt
    ) {
    }

    /** 服务目录条目视图。 */
    public record ServiceCatalogView(
            String serviceCode,
            String serviceName,
            String serviceType,
            String ownerTeam,
            String description
    ) {
    }

    /** 服务依赖边视图（供变更规划页展示串行依据）。 */
    public record ServiceDependencyView(
            String fromServiceCode,
            String toServiceCode,
            String dependencyType,
            String dependencyDesc
    ) {
    }
}
