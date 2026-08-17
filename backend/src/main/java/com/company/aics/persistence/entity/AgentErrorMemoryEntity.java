package com.company.aics.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Agent 错误记忆（{@code agent_error_memory}）：
 * 反思 Agent 判定的失败与纠正提示，供后续规划自我修正。
 */
@Entity
@Table(name = "agent_error_memory")
public class AgentErrorMemoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 被评判的角色：impact / dag / review / pipeline */
    @Column(name = "agent_role", nullable = false, length = 32)
    private String agentRole;

    /** 流水线阶段：impact / dag / review / final */
    @Column(name = "stage", nullable = false, length = 32)
    private String stage;

    /** 错误类型，如 empty_impact / cycle / missing_dep / quality */
    @Column(name = "error_type", nullable = false, length = 64)
    private String errorType;

    /** 错误详情 */
    @Column(name = "error_detail", nullable = false, columnDefinition = "TEXT")
    private String errorDetail;

    /** 纠正提示（下次应如何做） */
    @Column(name = "correction_hint", columnDefinition = "TEXT")
    private String correctionHint;

    /** 触发时的需求标题（便于检索相关教训） */
    @Column(name = "requirement_title", length = 255)
    private String requirementTitle;

    /** 关联规划 ID（可空，规划落库前记录则为空） */
    @Column(name = "plan_id")
    private Long planId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAgentRole() {
        return agentRole;
    }

    public void setAgentRole(String agentRole) {
        this.agentRole = agentRole;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public String getErrorType() {
        return errorType;
    }

    public void setErrorType(String errorType) {
        this.errorType = errorType;
    }

    public String getErrorDetail() {
        return errorDetail;
    }

    public void setErrorDetail(String errorDetail) {
        this.errorDetail = errorDetail;
    }

    public String getCorrectionHint() {
        return correctionHint;
    }

    public void setCorrectionHint(String correctionHint) {
        this.correctionHint = correctionHint;
    }

    public String getRequirementTitle() {
        return requirementTitle;
    }

    public void setRequirementTitle(String requirementTitle) {
        this.requirementTitle = requirementTitle;
    }

    public Long getPlanId() {
        return planId;
    }

    public void setPlanId(Long planId) {
        this.planId = planId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
