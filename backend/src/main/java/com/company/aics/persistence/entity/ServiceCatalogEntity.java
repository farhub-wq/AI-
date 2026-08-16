package com.company.aics.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 服务目录实体（{@code service_catalog}）：微服务编码、名称、类型与归属团队。
 */
@Entity
@Table(name = "service_catalog")
public class ServiceCatalogEntity {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 服务唯一编码。 */
    @Column(name = "service_code", nullable = false, unique = true, length = 64)
    private String serviceCode;

    /** 服务中文名。 */
    @Column(name = "service_name", nullable = false, length = 128)
    private String serviceName;

    /** 类型：backend / frontend 等。 */
    @Column(name = "service_type", nullable = false, length = 32)
    private String serviceType;

    /** 负责团队。 */
    @Column(name = "owner_team", length = 128)
    private String ownerTeam;

    /** 服务职责描述。 */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 更新时间。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    /** @return 主键 */
    public Long getId() { return id; }
    /** @param id 设置主键 */
    public void setId(Long id) { this.id = id; }
    /** @return 服务编码 */
    public String getServiceCode() { return serviceCode; }
    /** @param serviceCode 设置服务编码 */
    public void setServiceCode(String serviceCode) { this.serviceCode = serviceCode; }
    /** @return 服务名称 */
    public String getServiceName() { return serviceName; }
    /** @param serviceName 设置服务名称 */
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    /** @return 服务类型 */
    public String getServiceType() { return serviceType; }
    /** @param serviceType 设置服务类型 */
    public void setServiceType(String serviceType) { this.serviceType = serviceType; }
    /** @return 归属团队 */
    public String getOwnerTeam() { return ownerTeam; }
    /** @param ownerTeam 设置归属团队 */
    public void setOwnerTeam(String ownerTeam) { this.ownerTeam = ownerTeam; }
    /** @return 描述 */
    public String getDescription() { return description; }
    /** @param description 设置描述 */
    public void setDescription(String description) { this.description = description; }
    /** @return 创建时间 */
    public LocalDateTime getCreatedAt() { return createdAt; }
    /** @param createdAt 设置创建时间 */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    /** @return 更新时间 */
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    /** @param updatedAt 设置更新时间 */
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
