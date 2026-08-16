package com.company.aics.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 消息反馈实体（{@code message_feedback}）：对助手消息的点赞/点踩，按 message_id 唯一。
 */
@Entity
@Table(name = "message_feedback")
public class MessageFeedbackEntity {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 被反馈的助手消息 ID（唯一）。 */
    @Column(name = "message_id", nullable = false, unique = true)
    private Long messageId;

    /** 反馈用户 ID。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 评分（正/负）。 */
    @Column(nullable = false)
    private Integer rating;

    /** 原因码。 */
    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    /** 可选评论文本。 */
    @Column(length = 500)
    private String comment;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** @return 主键 */
    public Long getId() { return id; }
    /** @param id 设置主键 */
    public void setId(Long id) { this.id = id; }
    /** @return 消息 ID */
    public Long getMessageId() { return messageId; }
    /** @param messageId 设置消息 ID */
    public void setMessageId(Long messageId) { this.messageId = messageId; }
    /** @return 用户 ID */
    public Long getUserId() { return userId; }
    /** @param userId 设置用户 ID */
    public void setUserId(Long userId) { this.userId = userId; }
    /** @return 评分 */
    public Integer getRating() { return rating; }
    /** @param rating 设置评分 */
    public void setRating(Integer rating) { this.rating = rating; }
    /** @return 原因码 */
    public String getReasonCode() { return reasonCode; }
    /** @param reasonCode 设置原因码 */
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    /** @return 评论 */
    public String getComment() { return comment; }
    /** @param comment 设置评论 */
    public void setComment(String comment) { this.comment = comment; }
    /** @return 创建时间 */
    public LocalDateTime getCreatedAt() { return createdAt; }
    /** @param createdAt 设置创建时间 */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
