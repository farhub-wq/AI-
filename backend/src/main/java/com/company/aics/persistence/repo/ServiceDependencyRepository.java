package com.company.aics.persistence.repo;

import com.company.aics.persistence.entity.ServiceDependencyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 服务依赖表仓储：标准 CRUD。
 */
public interface ServiceDependencyRepository extends JpaRepository<ServiceDependencyEntity, Long> {
}
