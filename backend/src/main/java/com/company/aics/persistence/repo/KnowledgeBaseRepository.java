package com.company.aics.persistence.repo;

import com.company.aics.persistence.entity.KnowledgeBaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 知识库表仓储：标准 CRUD。
 */
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, Long> {
}
