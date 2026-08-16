package com.company.aics.persistence.repo;

import com.company.aics.persistence.entity.MessageEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 消息表仓储：按会话拉取消息、统计用户当日提问数。
 */
public interface MessageRepository extends JpaRepository<MessageEntity, Long> {

    /**
     * 按会话 ID 升序列出全部消息。
     */
    List<MessageEntity> findByConversationIdOrderByCreatedAtAsc(Long conversationId);

    /**
     * 统计指定用户在某时刻之后、指定角色的消息条数（用于日限流）。
     */
    long countByUserIdAndRoleAndCreatedAtGreaterThanEqual(Long userId, String role, LocalDateTime start);
}
