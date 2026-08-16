package com.company.aics.persistence.repo;

import com.company.aics.persistence.entity.MessageFeedbackEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 消息反馈表仓储：按消息 ID 唯一查找反馈。
 */
public interface MessageFeedbackRepository extends JpaRepository<MessageFeedbackEntity, Long> {

    /**
     * 按助手消息 ID 查找反馈（一消息至多一条）。
     */
    Optional<MessageFeedbackEntity> findByMessageId(Long messageId);
}
