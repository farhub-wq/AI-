package com.company.aics.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Agent 规划实体（{@code agent_plans}）：需求标题/正文、状态，以及任务等结构存于 plan_json。
 */
@Entity
@Table(name = "agent_plans")
public class AgentPlanEntity {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 创建该规划的用户 ID。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 需求标题。 */
    @Column(name = "requirement_title", nullable = false)
    private String requirementTitle;

    /** 需求正文。 */
    @Lob
    @Column(name = "requirement_content", nullable = false, columnDefinition = "LONGTEXT")
    private String requirementContent;

    /** 规划状态：success / partial / failed。 */
    @Column(nullable = false, length = 32)
    private String status;

    /** 受影响服务、任务、并行组等 JSON 载荷。 */
    @Column(name = "plan_json", nullable = false, columnDefinition = "JSON")
    private String planJson;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** @return 主键 */
    public Long getId() { return id; }
    /** @param id 设置主键 */
    public void setId(Long id) { this.id = id; }
    /** @return 用户 ID */
    public Long getUserId() { return userId; }
    /** @param userId 设置用户 ID */
    public void setUserId(Long userId) { this.userId = userId; }
    /** @return 需求标题 */
    public String getRequirementTitle() { return requirementTitle; }
    /** @param requirementTitle 设置需求标题 */
    public void setRequirementTitle(String requirementTitle) { this.requirementTitle = requirementTitle; }
    /** @return 需求正文 */
    public String getRequirementContent() { return requirementContent; }
    /** @param requirementContent 设置需求正文 */
    public void setRequirementContent(String requirementContent) { this.requirementContent = requirementContent; }
    /** @return 状态 */
    public String getStatus() { return status; }
    /** @param status 设置状态 */
    public void setStatus(String status) { this.status = status; }
    /** @return 规划 JSON */
    public String getPlanJson() { return planJson; }
    /** @param planJson 设置规划 JSON */
    public void setPlanJson(String planJson) { this.planJson = planJson; }
    /** @return 创建时间 */
    public LocalDateTime getCreatedAt() { return createdAt; }
    /** @param createdAt 设置创建时间 */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
