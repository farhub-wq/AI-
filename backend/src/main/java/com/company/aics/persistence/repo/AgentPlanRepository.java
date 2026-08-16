package com.company.aics.persistence.repo;

import com.company.aics.persistence.entity.AgentPlanEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Agent 规划表仓储：按用户列出规划。
 */
public interface AgentPlanRepository extends JpaRepository<AgentPlanEntity, Long> {

    /**
     * 列出指定用户的规划，按创建时间倒序。
     */
    List<AgentPlanEntity> findByUserIdOrderByCreatedAtDesc(Long userId);
}
