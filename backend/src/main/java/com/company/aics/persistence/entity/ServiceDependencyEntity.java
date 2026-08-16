package com.company.aics.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 服务依赖实体（{@code service_dependencies}）：上游→下游依赖类型与说明。
 */
@Entity
@Table(name = "service_dependencies")
public class ServiceDependencyEntity {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 上游服务编码。 */
    @Column(name = "from_service_code", nullable = false, length = 64)
    private String fromServiceCode;

    /** 下游服务编码。 */
    @Column(name = "to_service_code", nullable = false, length = 64)
    private String toServiceCode;

    /** 依赖类型：event / data / api / config 等。 */
    @Column(name = "dependency_type", nullable = false, length = 32)
    private String dependencyType;

    /** 依赖关系说明。 */
    @Column(name = "dependency_desc", columnDefinition = "TEXT")
    private String dependencyDesc;

    /** @return 主键 */
    public Long getId() { return id; }
    /** @param id 设置主键 */
    public void setId(Long id) { this.id = id; }
    /** @return 上游服务编码 */
    public String getFromServiceCode() { return fromServiceCode; }
    /** @param fromServiceCode 设置上游服务编码 */
    public void setFromServiceCode(String fromServiceCode) { this.fromServiceCode = fromServiceCode; }
    /** @return 下游服务编码 */
    public String getToServiceCode() { return toServiceCode; }
    /** @param toServiceCode 设置下游服务编码 */
    public void setToServiceCode(String toServiceCode) { this.toServiceCode = toServiceCode; }
    /** @return 依赖类型 */
    public String getDependencyType() { return dependencyType; }
    /** @param dependencyType 设置依赖类型 */
    public void setDependencyType(String dependencyType) { this.dependencyType = dependencyType; }
    /** @return 依赖说明 */
    public String getDependencyDesc() { return dependencyDesc; }
    /** @param dependencyDesc 设置依赖说明 */
    public void setDependencyDesc(String dependencyDesc) { this.dependencyDesc = dependencyDesc; }
}
