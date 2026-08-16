package com.company.aics.persistence.repo;

import com.company.aics.persistence.entity.ServiceCatalogEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 服务目录表仓储：按服务编码查找。
 */
public interface ServiceCatalogRepository extends JpaRepository<ServiceCatalogEntity, Long> {

    /**
     * 按唯一 serviceCode 查找目录项。
     */
    Optional<ServiceCatalogEntity> findByServiceCode(String serviceCode);
}
