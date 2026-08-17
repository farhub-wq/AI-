package com.company.aics.persistence.repo;

import com.company.aics.persistence.entity.AgentErrorMemoryEntity;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentErrorMemoryRepository extends JpaRepository<AgentErrorMemoryEntity, Long> {

    List<AgentErrorMemoryEntity> findByAgentRoleOrderByCreatedAtDesc(String agentRole, Pageable pageable);

    List<AgentErrorMemoryEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
