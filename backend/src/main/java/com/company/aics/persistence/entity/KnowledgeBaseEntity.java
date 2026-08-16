package com.company.aics.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 知识库实体（{@code knowledge_bases}）：名称、类型与描述元数据。
 */
@Entity
@Table(name = "knowledge_bases")
public class KnowledgeBaseEntity {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 知识库名称。 */
    @Column(nullable = false, length = 128)
    private String name;

    /** 类型，如 customer_support / technical_docs。 */
    @Column(name = "kb_type", nullable = false, length = 64)
    private String kbType;

    /** 描述说明。 */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** @return 主键 */
    public Long getId() { return id; }
    /** @param id 设置主键 */
    public void setId(Long id) { this.id = id; }
    /** @return 名称 */
    public String getName() { return name; }
    /** @param name 设置名称 */
    public void setName(String name) { this.name = name; }
    /** @return 类型 */
    public String getKbType() { return kbType; }
    /** @param kbType 设置类型 */
    public void setKbType(String kbType) { this.kbType = kbType; }
    /** @return 描述 */
    public String getDescription() { return description; }
    /** @param description 设置描述 */
    public void setDescription(String description) { this.description = description; }
    /** @return 创建时间 */
    public LocalDateTime getCreatedAt() { return createdAt; }
    /** @param createdAt 设置创建时间 */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
