package com.company.aics.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Refresh Token 实体（表 {@code refresh_tokens}）：仅存哈希，支持轮换与吊销。
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshTokenEntity {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属用户 ID。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Refresh 原文的 SHA-256 十六进制哈希（唯一）。 */
    @Column(name = "token_hash", nullable = false, length = 128, unique = true)
    private String tokenHash;

    /** 过期时间。 */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** 是否已吊销（登出或轮换后为 true）。 */
    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public void setRevoked(boolean revoked) {
        this.revoked = revoked;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
