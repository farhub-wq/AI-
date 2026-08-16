package com.company.aics.application;

import com.company.aics.domain.DomainModels;
import com.company.aics.persistence.AppDataStore;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 会话应用服务：创建/列表/详情、消息追加与助手消息更新、当日提问计数与历史轮次截取。
 * 所有会话读写均校验归属用户，防止跨用户访问；数据存放于 MySQL（{@link AppDataStore}）。
 */
@Service
public class ConversationService {

    private final AppDataStore appDataStore;

    /**
     * @param appDataStore MySQL 数据访问门面
     */
    public ConversationService(AppDataStore appDataStore) {
        this.appDataStore = appDataStore;
    }

    /**
     * 创建新会话；知识库缺省为 1，标题为空时使用「新会话」。
     */
    public DomainModels.Conversation createConversation(Long userId, String title, Long kbId) {
        Long resolvedKbId = kbId == null ? 1L : kbId;
        if (!appDataStore.knowledgeBaseExists(resolvedKbId)) {
            throw new IllegalArgumentException("Knowledge base does not exist.");
        }

        OffsetDateTime currentTime = now();
        String finalTitle = StringUtils.hasText(title) ? title.trim() : "新会话";
        return appDataStore.saveConversation(new DomainModels.Conversation(
                null,
                userId,
                resolvedKbId,
                finalTitle,
                null,
                currentTime,
                currentTime
        ));
    }

    /**
     * 分页列出指定用户的会话（按更新时间倒序）。
     */
    public List<DomainModels.Conversation> listUserConversations(Long userId, int page, int pageSize) {
        return appDataStore.listConversationsByUser(userId).stream()
                .skip((long) Math.max(0, page - 1) * pageSize)
                .limit(pageSize)
                .toList();
    }

    /**
     * 获取会话详情（含按时间排序的消息）。
     */
    public ConversationDetail getConversationDetail(Long userId, Long conversationId) {
        DomainModels.Conversation conversation = requireConversation(userId, conversationId);
        return new ConversationDetail(conversation, listMessages(conversationId));
    }

    /**
     * 若传入会话 ID 则校验归属并返回；否则按问题自动创建会话。
     */
    public DomainModels.Conversation ensureConversation(Long userId, Long conversationId, Long kbId, String question) {
        if (conversationId != null) {
            return requireConversation(userId, conversationId);
        }
        return createConversation(userId, autoTitle(question), kbId);
    }

    /**
     * 更新会话绑定的知识库（用于多库自动路由后回写）。
     */
    public DomainModels.Conversation updateConversationKb(Long userId, Long conversationId, Long kbId) {
        DomainModels.Conversation conversation = requireConversation(userId, conversationId);
        if (!appDataStore.knowledgeBaseExists(kbId)) {
            throw new IllegalArgumentException("Knowledge base does not exist.");
        }
        return appDataStore.saveConversation(new DomainModels.Conversation(
                conversation.id(),
                conversation.userId(),
                kbId,
                conversation.title(),
                conversation.lastIntent(),
                conversation.createdAt(),
                now()
        ));
    }

    /**
     * 列出会话全部消息（按创建时间升序）。
     */
    public List<DomainModels.Message> listMessages(Long conversationId) {
        return appDataStore.listMessagesByConversation(conversationId);
    }

    /**
     * 截取最近 N 轮对话（一轮≈用户+助手共 2 条）。
     */
    public List<DomainModels.Message> listRecentMessages(Long conversationId, int rounds) {
        List<DomainModels.Message> messages = listMessages(conversationId);
        int limit = Math.max(0, rounds) * 2;
        if (limit == 0 || messages.size() <= limit) {
            return messages;
        }
        return messages.subList(messages.size() - limit, messages.size());
    }

    /**
     * 统计用户当日 USER 角色提问条数，供日限流使用。
     */
    public long countUserQuestionsToday(Long userId) {
        return appDataStore.countUserQuestionsToday(userId);
    }

    /**
     * 追加用户消息。
     */
    public DomainModels.Message addUserMessage(Long conversationId, Long userId, String content, String traceId) {
        return addMessage(
                conversationId,
                userId,
                DomainModels.MessageRole.USER,
                content,
                List.of(),
                null,
                null,
                0,
                0.0,
                0,
                traceId
        );
    }

    /**
     * 追加助手消息（含引用与检索观测字段）。
     */
    public DomainModels.Message addAssistantMessage(
            Long conversationId,
            Long userId,
            String content,
            List<DomainModels.Citation> citations,
            String intentLabel,
            String answerStatus,
            int retrievalCount,
            double topScore,
            int latencyMs,
            String traceId
    ) {
        return addMessage(
                conversationId,
                userId,
                DomainModels.MessageRole.ASSISTANT,
                content,
                citations,
                intentLabel,
                answerStatus,
                retrievalCount,
                topScore,
                latencyMs,
                traceId
        );
    }

    /**
     * 流式生成结束后更新助手消息内容与观测字段，并刷新会话 lastIntent/updatedAt。
     */
    public DomainModels.Message updateAssistantMessage(
            Long messageId,
            String content,
            List<DomainModels.Citation> citations,
            String intentLabel,
            String answerStatus,
            int retrievalCount,
            double topScore,
            int latencyMs
    ) {
        DomainModels.Message existing = appDataStore.findMessage(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Assistant message does not exist."));
        if (existing.role() != DomainModels.MessageRole.ASSISTANT) {
            throw new IllegalArgumentException("Assistant message does not exist.");
        }
        DomainModels.Message updated = appDataStore.saveMessage(new DomainModels.Message(
                existing.id(),
                existing.conversationId(),
                existing.userId(),
                existing.role(),
                content,
                citations,
                intentLabel,
                answerStatus,
                retrievalCount,
                topScore,
                latencyMs,
                existing.traceId(),
                existing.createdAt()
        ));

        appDataStore.findConversation(existing.conversationId()).ifPresent(conversation ->
                appDataStore.saveConversation(new DomainModels.Conversation(
                        conversation.id(),
                        conversation.userId(),
                        conversation.kbId(),
                        conversation.title(),
                        intentLabel != null ? intentLabel : conversation.lastIntent(),
                        conversation.createdAt(),
                        now()
                ))
        );
        return updated;
    }

    /**
     * 内部统一落库消息并更新会话时间戳。
     */
    private DomainModels.Message addMessage(
            Long conversationId,
            Long userId,
            DomainModels.MessageRole role,
            String content,
            List<DomainModels.Citation> citations,
            String intentLabel,
            String answerStatus,
            int retrievalCount,
            double topScore,
            int latencyMs,
            String traceId
    ) {
        DomainModels.Conversation conversation = requireConversation(userId, conversationId);
        DomainModels.Message message = appDataStore.saveMessage(new DomainModels.Message(
                null,
                conversationId,
                userId,
                role,
                content,
                citations,
                intentLabel,
                answerStatus,
                retrievalCount,
                topScore,
                latencyMs,
                traceId,
                now()
        ));

        String lastIntent = intentLabel != null ? intentLabel : conversation.lastIntent();
        appDataStore.saveConversation(new DomainModels.Conversation(
                conversation.id(),
                conversation.userId(),
                conversation.kbId(),
                conversation.title(),
                lastIntent,
                conversation.createdAt(),
                now()
        ));
        return message;
    }

    /**
     * 校验会话存在且属于当前用户。
     */
    private DomainModels.Conversation requireConversation(Long userId, Long conversationId) {
        DomainModels.Conversation conversation = appDataStore.findConversation(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation does not exist or access is denied."));
        if (!Objects.equals(conversation.userId(), userId)) {
            throw new IllegalArgumentException("Conversation does not exist or access is denied.");
        }
        return conversation;
    }

    /**
     * 由首问压缩生成会话标题（最多 16 字）。
     */
    private String autoTitle(String question) {
        if (!StringUtils.hasText(question)) {
            return "新会话";
        }
        String compact = question.replaceAll("\\s+", "").trim();
        return compact.length() > 16 ? compact.substring(0, 16) : compact;
    }

    /** @return 东八区当前时间 */
    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.ofHours(8));
    }

    /** 会话详情聚合：会话元数据 + 消息列表。 */
    public record ConversationDetail(DomainModels.Conversation conversation, List<DomainModels.Message> messages) {
    }
}
