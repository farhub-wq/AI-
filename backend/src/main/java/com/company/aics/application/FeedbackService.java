package com.company.aics.application;

import com.company.aics.domain.DomainModels;
import com.company.aics.persistence.AppDataStore;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 消息反馈应用服务：对助手消息提交点赞/点踩，已有反馈则覆盖更新。
 * 校验消息存在、角色为 ASSISTANT，且仅消息所属用户可反馈；持久化经 {@link AppDataStore}。
 */
@Service
public class FeedbackService {

    private final AppDataStore appDataStore;

    /**
     * @param appDataStore MySQL 数据访问门面
     */
    public FeedbackService(AppDataStore appDataStore) {
        this.appDataStore = appDataStore;
    }

    /**
     * 提交或覆盖某条助手消息的反馈记录。
     *
     * @param userId     当前用户
     * @param messageId  助手消息 ID
     * @param rating     评分（正/负）
     * @param reasonCode 原因码
     * @param comment    可选评论
     * @return 持久化后的反馈
     */
    public DomainModels.MessageFeedback submitFeedback(
            Long userId,
            Long messageId,
            int rating,
            String reasonCode,
            String comment
    ) {
        DomainModels.Message message = appDataStore.findMessage(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Message does not exist."));
        // 仅允许对助手回答反馈
        if (message.role() != DomainModels.MessageRole.ASSISTANT) {
            throw new IllegalArgumentException("Feedback can only be submitted for assistant messages.");
        }
        if (!Objects.equals(message.userId(), userId)) {
            throw new IllegalArgumentException("You do not have permission to rate this message.");
        }

        // AppDataStore 按 messageId 覆盖更新；id 传 null 即可
        return appDataStore.saveFeedback(new DomainModels.MessageFeedback(
                null,
                messageId,
                userId,
                rating,
                reasonCode,
                comment,
                now()
        ));
    }

    /** @return 东八区当前时间 */
    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.ofHours(8));
    }
}
