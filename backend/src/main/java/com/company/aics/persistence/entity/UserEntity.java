package com.company.aics.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 用户表实体（{@code users}）：邮箱/手机登录、密码哈希与展示名。
 */
@Entity
@Table(name = "users")
public class UserEntity {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 登录邮箱（唯一）。 */
    @Column(nullable = false, unique = true, length = 128)
    private String email;

    /** 手机号（可选、唯一）。 */
    @Column(unique = true, length = 32)
    private String phone;

    /** BCrypt 等编码后的密码哈希。 */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /** 对外展示名称。 */
    @Column(name = "display_name", nullable = false, length = 64)
    private String displayName;

    /** 账号状态（1=正常）。 */
    @Column(nullable = false)
    private Integer status = 1;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** @return 主键 */
    public Long getId() { return id; }
    /** @param id 设置主键 */
    public void setId(Long id) { this.id = id; }
    /** @return 邮箱 */
    public String getEmail() { return email; }
    /** @param email 设置邮箱 */
    public void setEmail(String email) { this.email = email; }
    /** @return 手机号 */
    public String getPhone() { return phone; }
    /** @param phone 设置手机号 */
    public void setPhone(String phone) { this.phone = phone; }
    /** @return 密码哈希 */
    public String getPasswordHash() { return passwordHash; }
    /** @param passwordHash 设置密码哈希 */
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    /** @return 展示名 */
    public String getDisplayName() { return displayName; }
    /** @param displayName 设置展示名 */
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    /** @return 状态 */
    public Integer getStatus() { return status; }
    /** @param status 设置状态 */
    public void setStatus(Integer status) { this.status = status; }
    /** @return 创建时间 */
    public LocalDateTime getCreatedAt() { return createdAt; }
    /** @param createdAt 设置创建时间 */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
