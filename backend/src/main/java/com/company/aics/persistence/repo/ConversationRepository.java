package com.company.aics.persistence.repo;

import com.company.aics.persistence.entity.ConversationEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 会话表仓储：按用户查询会话列表。
 */
public interface ConversationRepository extends JpaRepository<ConversationEntity, Long> {

    /**
     * 列出指定用户的会话，按更新时间倒序。
     */
    List<ConversationEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);
}
