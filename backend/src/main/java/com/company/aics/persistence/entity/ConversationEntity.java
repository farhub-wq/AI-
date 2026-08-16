package com.company.aics.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 会话表实体（{@code conversations}）：归属用户、绑定知识库与末次意图。
 */
@Entity
@Table(name = "conversations")
public class ConversationEntity {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属用户 ID。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 绑定的知识库 ID。 */
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /** 会话标题。 */
    @Column(nullable = false)
    private String title;

    /** 最近一次意图标签。 */
    @Column(name = "last_intent", length = 64)
    private String lastIntent;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 最近更新时间。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    /** @return 主键 */
    public Long getId() { return id; }
    /** @param id 设置主键 */
    public void setId(Long id) { this.id = id; }
    /** @return 用户 ID */
    public Long getUserId() { return userId; }
    /** @param userId 设置用户 ID */
    public void setUserId(Long userId) { this.userId = userId; }
    /** @return 知识库 ID */
    public Long getKbId() { return kbId; }
    /** @param kbId 设置知识库 ID */
    public void setKbId(Long kbId) { this.kbId = kbId; }
    /** @return 标题 */
    public String getTitle() { return title; }
    /** @param title 设置标题 */
    public void setTitle(String title) { this.title = title; }
    /** @return 末次意图 */
    public String getLastIntent() { return lastIntent; }
    /** @param lastIntent 设置末次意图 */
    public void setLastIntent(String lastIntent) { this.lastIntent = lastIntent; }
    /** @return 创建时间 */
    public LocalDateTime getCreatedAt() { return createdAt; }
    /** @param createdAt 设置创建时间 */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    /** @return 更新时间 */
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    /** @param updatedAt 设置更新时间 */
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
