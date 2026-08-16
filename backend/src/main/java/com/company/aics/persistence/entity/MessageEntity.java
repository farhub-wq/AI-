package com.company.aics.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 消息表实体（{@code messages}）：会话内用户/助手消息及检索观测字段。
 */
@Entity
@Table(name = "messages")
public class MessageEntity {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属会话 ID。 */
    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    /** 消息所属用户 ID。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 角色：USER / ASSISTANT。 */
    @Column(nullable = false, length = 32)
    private String role;

    /** 消息正文。 */
    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    /** 引用列表 JSON。 */
    @Column(name = "citations_json", columnDefinition = "JSON")
    private String citationsJson;

    /** 意图标签。 */
    @Column(name = "intent_label", length = 64)
    private String intentLabel;

    /** 回答状态：streaming/success/fallback/degraded 等。 */
    @Column(name = "answer_status", length = 32)
    private String answerStatus;

    /** 检索命中条数。 */
    @Column(name = "retrieval_count", nullable = false)
    private Integer retrievalCount = 0;

    /** 最高检索分数。 */
    @Column(name = "top_score", nullable = false, precision = 10, scale = 4)
    private BigDecimal topScore = BigDecimal.ZERO;

    /** 端到端延迟（毫秒）。 */
    @Column(name = "latency_ms", nullable = false)
    private Integer latencyMs = 0;

    /** 链路追踪 ID。 */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** @return 主键 */
    public Long getId() { return id; }
    /** @param id 设置主键 */
    public void setId(Long id) { this.id = id; }
    /** @return 会话 ID */
    public Long getConversationId() { return conversationId; }
    /** @param conversationId 设置会话 ID */
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    /** @return 用户 ID */
    public Long getUserId() { return userId; }
    /** @param userId 设置用户 ID */
    public void setUserId(Long userId) { this.userId = userId; }
    /** @return 角色 */
    public String getRole() { return role; }
    /** @param role 设置角色 */
    public void setRole(String role) { this.role = role; }
    /** @return 正文 */
    public String getContent() { return content; }
    /** @param content 设置正文 */
    public void setContent(String content) { this.content = content; }
    /** @return 引用 JSON */
    public String getCitationsJson() { return citationsJson; }
    /** @param citationsJson 设置引用 JSON */
    public void setCitationsJson(String citationsJson) { this.citationsJson = citationsJson; }
    /** @return 意图标签 */
    public String getIntentLabel() { return intentLabel; }
    /** @param intentLabel 设置意图标签 */
    public void setIntentLabel(String intentLabel) { this.intentLabel = intentLabel; }
    /** @return 回答状态 */
    public String getAnswerStatus() { return answerStatus; }
    /** @param answerStatus 设置回答状态 */
    public void setAnswerStatus(String answerStatus) { this.answerStatus = answerStatus; }
    /** @return 检索条数 */
    public Integer getRetrievalCount() { return retrievalCount; }
    /** @param retrievalCount 设置检索条数 */
    public void setRetrievalCount(Integer retrievalCount) { this.retrievalCount = retrievalCount; }
    /** @return 最高分 */
    public BigDecimal getTopScore() { return topScore; }
    /** @param topScore 设置最高分 */
    public void setTopScore(BigDecimal topScore) { this.topScore = topScore; }
    /** @return 延迟毫秒 */
    public Integer getLatencyMs() { return latencyMs; }
    /** @param latencyMs 设置延迟毫秒 */
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }
    /** @return 追踪 ID */
    public String getTraceId() { return traceId; }
    /** @param traceId 设置追踪 ID */
    public void setTraceId(String traceId) { this.traceId = traceId; }
    /** @return 创建时间 */
    public LocalDateTime getCreatedAt() { return createdAt; }
    /** @param createdAt 设置创建时间 */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
