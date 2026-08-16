package com.company.aics.persistence;

import com.company.aics.domain.DomainModels;
import com.company.aics.persistence.entity.AgentPlanEntity;
import com.company.aics.persistence.entity.ConversationEntity;
import com.company.aics.persistence.entity.DocumentChunkEntity;
import com.company.aics.persistence.entity.KnowledgeBaseEntity;
import com.company.aics.persistence.entity.KnowledgeDocumentEntity;
import com.company.aics.persistence.entity.MessageEntity;
import com.company.aics.persistence.entity.MessageFeedbackEntity;
import com.company.aics.persistence.entity.ServiceCatalogEntity;
import com.company.aics.persistence.entity.ServiceDependencyEntity;
import com.company.aics.persistence.entity.UserEntity;
import com.company.aics.persistence.repo.AgentPlanRepository;
import com.company.aics.persistence.repo.ConversationRepository;
import com.company.aics.persistence.repo.DocumentChunkRepository;
import com.company.aics.persistence.repo.KnowledgeBaseRepository;
import com.company.aics.persistence.repo.KnowledgeDocumentRepository;
import com.company.aics.persistence.repo.MessageFeedbackRepository;
import com.company.aics.persistence.repo.MessageRepository;
import com.company.aics.persistence.repo.ServiceCatalogRepository;
import com.company.aics.persistence.repo.ServiceDependencyRepository;
import com.company.aics.persistence.repo.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 应用数据访问门面：封装各 JPA Repository，对外以领域模型读写，屏蔽实体细节。
 * 文档保存时同步重建切块；反馈按 messageId 覆盖更新。
 */
@Service
public class AppDataStore {

    /** Agent 任务 ID 本地序列（任务嵌在 plan_json 中，无独立表）。 */
    private final AtomicLong agentTaskSequence = new AtomicLong(1);

    private final UserRepository userRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final MessageFeedbackRepository feedbackRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final ServiceCatalogRepository serviceCatalogRepository;
    private final ServiceDependencyRepository serviceDependencyRepository;
    private final AgentPlanRepository agentPlanRepository;
    private final DomainMapper mapper;

    /**
     * 注入全部仓储与领域映射器。
     */
    public AppDataStore(
            UserRepository userRepository,
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            MessageFeedbackRepository feedbackRepository,
            KnowledgeBaseRepository knowledgeBaseRepository,
            KnowledgeDocumentRepository knowledgeDocumentRepository,
            DocumentChunkRepository documentChunkRepository,
            ServiceCatalogRepository serviceCatalogRepository,
            ServiceDependencyRepository serviceDependencyRepository,
            AgentPlanRepository agentPlanRepository,
            DomainMapper mapper
    ) {
        this.userRepository = userRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.feedbackRepository = feedbackRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.serviceCatalogRepository = serviceCatalogRepository;
        this.serviceDependencyRepository = serviceDependencyRepository;
        this.agentPlanRepository = agentPlanRepository;
        this.mapper = mapper;
    }

    /** @return 下一个 Agent 任务 ID */
    public long nextAgentTaskId() {
        return agentTaskSequence.getAndIncrement();
    }

    /** 按 ID 查找用户。 */
    @Transactional(readOnly = true)
    public Optional<DomainModels.User> findUserById(Long userId) {
        return userRepository.findById(userId).map(mapper::toUser);
    }

    /** 按邮箱或手机号查找用户。 */
    @Transactional(readOnly = true)
    public Optional<DomainModels.User> findUserByAccount(String account) {
        return userRepository.findFirstByEmailOrPhoneOrderByIdAsc(account, account).map(mapper::toUser);
    }

    /** 邮箱是否已注册。 */
    @Transactional(readOnly = true)
    public boolean emailExists(String email) {
        return userRepository.existsByEmail(email);
    }

    /** 手机号是否已注册。 */
    @Transactional(readOnly = true)
    public boolean phoneExists(String phone) {
        return userRepository.existsByPhone(phone);
    }

    /**
     * 新建或更新用户；id 为空则新建实体。
     */
    @Transactional
    public DomainModels.User saveUser(DomainModels.User user) {
        UserEntity entity = user.id() == null ? new UserEntity() : userRepository.findById(user.id()).orElseGet(UserEntity::new);
        entity.setEmail(user.email());
        entity.setPhone(user.phone());
        entity.setPasswordHash(user.passwordHash());
        entity.setDisplayName(user.displayName());
        entity.setStatus(user.status());
        if (user.createdAt() != null) {
            entity.setCreatedAt(mapper.toLocal(user.createdAt()));
        }
        return mapper.toUser(userRepository.save(entity));
    }

    /** 知识库是否存在。 */
    @Transactional(readOnly = true)
    public boolean knowledgeBaseExists(Long kbId) {
        return knowledgeBaseRepository.existsById(kbId);
    }

    /** 按 ID 查找知识库。 */
    @Transactional(readOnly = true)
    public Optional<DomainModels.KnowledgeBase> findKnowledgeBase(Long kbId) {
        return knowledgeBaseRepository.findById(kbId).map(mapper::toKnowledgeBase);
    }

    /** 列出全部知识库（创建时间倒序）。 */
    @Transactional(readOnly = true)
    public List<DomainModels.KnowledgeBase> listKnowledgeBases() {
        return knowledgeBaseRepository.findAll().stream()
                .map(mapper::toKnowledgeBase)
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .toList();
    }

    /** 新建或更新知识库。 */
    @Transactional
    public DomainModels.KnowledgeBase saveKnowledgeBase(DomainModels.KnowledgeBase knowledgeBase) {
        KnowledgeBaseEntity entity = knowledgeBase.id() == null
                ? new KnowledgeBaseEntity()
                : knowledgeBaseRepository.findById(knowledgeBase.id()).orElseGet(KnowledgeBaseEntity::new);
        entity.setName(knowledgeBase.name());
        entity.setKbType(knowledgeBase.kbType());
        entity.setDescription(knowledgeBase.description());
        if (knowledgeBase.createdAt() != null) {
            entity.setCreatedAt(mapper.toLocal(knowledgeBase.createdAt()));
        }
        return mapper.toKnowledgeBase(knowledgeBaseRepository.save(entity));
    }

    /** 新建或更新会话。 */
    @Transactional
    public DomainModels.Conversation saveConversation(DomainModels.Conversation conversation) {
        ConversationEntity entity = conversation.id() == null
                ? new ConversationEntity()
                : conversationRepository.findById(conversation.id()).orElseGet(ConversationEntity::new);
        entity.setUserId(conversation.userId());
        entity.setKbId(conversation.kbId());
        entity.setTitle(conversation.title());
        entity.setLastIntent(conversation.lastIntent());
        if (conversation.createdAt() != null) {
            entity.setCreatedAt(mapper.toLocal(conversation.createdAt()));
        }
        entity.setUpdatedAt(mapper.toLocal(conversation.updatedAt()));
        return mapper.toConversation(conversationRepository.save(entity));
    }

    /** 按 ID 查找会话。 */
    @Transactional(readOnly = true)
    public Optional<DomainModels.Conversation> findConversation(Long conversationId) {
        return conversationRepository.findById(conversationId).map(mapper::toConversation);
    }

    /** 列出用户会话（更新时间倒序）。 */
    @Transactional(readOnly = true)
    public List<DomainModels.Conversation> listConversationsByUser(Long userId) {
        return conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(mapper::toConversation)
                .toList();
    }

    /** 列出全站会话（更新时间倒序）。 */
    @Transactional(readOnly = true)
    public List<DomainModels.Conversation> listAllConversations() {
        return conversationRepository.findAll().stream()
                .map(mapper::toConversation)
                .sorted((a, b) -> b.updatedAt().compareTo(a.updatedAt()))
                .toList();
    }

    /** 新建或更新消息（含引用 JSON 与观测字段）。 */
    @Transactional
    public DomainModels.Message saveMessage(DomainModels.Message message) {
        MessageEntity entity = message.id() == null
                ? new MessageEntity()
                : messageRepository.findById(message.id()).orElseGet(MessageEntity::new);
        entity.setConversationId(message.conversationId());
        entity.setUserId(message.userId());
        entity.setRole(message.role().name());
        entity.setContent(message.content());
        entity.setCitationsJson(mapper.writeCitations(message.citations()));
        entity.setIntentLabel(message.intentLabel());
        entity.setAnswerStatus(message.answerStatus());
        entity.setRetrievalCount(message.retrievalCount() == null ? 0 : message.retrievalCount());
        entity.setTopScore(mapper.toDecimal(message.topScore() == null ? 0.0 : message.topScore()));
        entity.setLatencyMs(message.latencyMs() == null ? 0 : message.latencyMs());
        entity.setTraceId(message.traceId());
        if (message.createdAt() != null) {
            entity.setCreatedAt(mapper.toLocal(message.createdAt()));
        }
        return mapper.toMessage(messageRepository.save(entity));
    }

    /** 按 ID 查找消息。 */
    @Transactional(readOnly = true)
    public Optional<DomainModels.Message> findMessage(Long messageId) {
        return messageRepository.findById(messageId).map(mapper::toMessage);
    }

    /** 按会话升序列出消息。 */
    @Transactional(readOnly = true)
    public List<DomainModels.Message> listMessagesByConversation(Long conversationId) {
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .map(mapper::toMessage)
                .toList();
    }

    /** 列出全部消息。 */
    @Transactional(readOnly = true)
    public List<DomainModels.Message> listAllMessages() {
        return messageRepository.findAll().stream().map(mapper::toMessage).toList();
    }

    /**
     * 统计用户当日 USER 消息数（从当天 00:00 起）。
     */
    @Transactional(readOnly = true)
    public long countUserQuestionsToday(Long userId) {
        LocalDateTime start = LocalDate.now().atTime(LocalTime.MIN);
        return messageRepository.countByUserIdAndRoleAndCreatedAtGreaterThanEqual(userId, DomainModels.MessageRole.USER.name(), start);
    }

    /**
     * 保存反馈：若该消息已有反馈则覆盖更新。
     */
    @Transactional
    public DomainModels.MessageFeedback saveFeedback(DomainModels.MessageFeedback feedback) {
        MessageFeedbackEntity entity = feedbackRepository.findByMessageId(feedback.messageId())
                .orElseGet(MessageFeedbackEntity::new);
        entity.setMessageId(feedback.messageId());
        entity.setUserId(feedback.userId());
        entity.setRating(feedback.rating());
        entity.setReasonCode(feedback.reasonCode());
        entity.setComment(feedback.comment());
        if (feedback.createdAt() != null) {
            entity.setCreatedAt(mapper.toLocal(feedback.createdAt()));
        }
        return mapper.toFeedback(feedbackRepository.save(entity));
    }

    /** 按消息 ID 查找反馈。 */
    @Transactional(readOnly = true)
    public Optional<DomainModels.MessageFeedback> findFeedbackByMessageId(Long messageId) {
        return feedbackRepository.findByMessageId(messageId).map(mapper::toFeedback);
    }

    /** 列出全部反馈。 */
    @Transactional(readOnly = true)
    public List<DomainModels.MessageFeedback> listAllFeedback() {
        return feedbackRepository.findAll().stream().map(mapper::toFeedback).toList();
    }

    /**
     * 保存文档元数据并全量重建切块：更新时先删旧切块再写入新切块。
     */
    @Transactional
    public DomainModels.KnowledgeDocument saveDocument(DomainModels.KnowledgeDocument document) {
        KnowledgeDocumentEntity entity = document.id() == null
                ? new KnowledgeDocumentEntity()
                : knowledgeDocumentRepository.findById(document.id()).orElseGet(KnowledgeDocumentEntity::new);
        entity.setKbId(document.kbId());
        entity.setFileName(document.fileName());
        entity.setFileExt(document.fileExt());
        entity.setDocType(document.docType());
        entity.setContentHash(document.contentHash());
        entity.setStatus(document.status());
        entity.setPriority(document.priority());
        entity.setServiceCode(document.serviceCode());
        if (document.uploadedAt() != null) {
            entity.setUploadedAt(mapper.toLocal(document.uploadedAt()));
        }
        KnowledgeDocumentEntity saved = knowledgeDocumentRepository.save(entity);

        // 已有文档：删除旧切块后全量重建，保证与向量索引一致
        if (document.id() != null) {
            documentChunkRepository.deleteByDocumentId(saved.getId());
        }

        List<DomainModels.DocumentChunk> savedChunks = new ArrayList<>();
        for (DomainModels.DocumentChunk chunk : document.chunks()) {
            DocumentChunkEntity chunkEntity = new DocumentChunkEntity();
            chunkEntity.setDocumentId(saved.getId());
            chunkEntity.setKbId(saved.getKbId());
            chunkEntity.setVectorId(chunk.vectorId());
            chunkEntity.setChunkIndex(chunk.chunkIndex());
            chunkEntity.setSectionTitle(chunk.sectionTitle());
            chunkEntity.setPriority(chunk.priority());
            chunkEntity.setContent(chunk.content());
            chunkEntity.setMetadataJson(mapper.writeMetadata(chunk.metadata()));
            savedChunks.add(mapper.toChunk(documentChunkRepository.save(chunkEntity)));
        }
        return mapper.toDocument(saved, savedChunks);
    }

    /**
     * 仅更新文档处理状态（processing / ready / failed），不重建切块。
     */
    @Transactional
    public DomainModels.KnowledgeDocument updateDocumentStatus(Long documentId, String status) {
        KnowledgeDocumentEntity entity = knowledgeDocumentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在。"));
        entity.setStatus(status);
        KnowledgeDocumentEntity saved = knowledgeDocumentRepository.save(entity);
        return loadDocument(saved);
    }

    /** 按 ID 加载文档（含切块）。 */
    @Transactional(readOnly = true)
    public Optional<DomainModels.KnowledgeDocument> findDocument(Long documentId) {
        return knowledgeDocumentRepository.findById(documentId).map(this::loadDocument);
    }

    /** 按知识库列出文档（含切块）。 */
    @Transactional(readOnly = true)
    public List<DomainModels.KnowledgeDocument> listDocumentsByKb(Long kbId) {
        return knowledgeDocumentRepository.findByKbIdOrderByUploadedAtDesc(kbId).stream()
                .map(this::loadDocument)
                .toList();
    }

    /** 列出全部文档（含切块）。 */
    @Transactional(readOnly = true)
    public List<DomainModels.KnowledgeDocument> listAllDocuments() {
        return knowledgeDocumentRepository.findAll().stream().map(this::loadDocument).toList();
    }

    /**
     * 删除文档：先删切块再删文档元数据。
     */
    @Transactional
    public void deleteDocument(Long documentId) {
        documentChunkRepository.deleteByDocumentId(documentId);
        knowledgeDocumentRepository.deleteById(documentId);
    }

    /** 列出服务目录。 */
    @Transactional(readOnly = true)
    public List<DomainModels.ServiceCatalogItem> listServiceCatalog() {
        return serviceCatalogRepository.findAll().stream().map(mapper::toServiceCatalog).toList();
    }

    /** 新建或更新服务目录项。 */
    @Transactional
    public DomainModels.ServiceCatalogItem saveServiceCatalog(DomainModels.ServiceCatalogItem item) {
        ServiceCatalogEntity entity = item.id() == null
                ? new ServiceCatalogEntity()
                : serviceCatalogRepository.findById(item.id()).orElseGet(ServiceCatalogEntity::new);
        entity.setServiceCode(item.serviceCode());
        entity.setServiceName(item.serviceName());
        entity.setServiceType(item.serviceType());
        entity.setOwnerTeam(item.ownerTeam());
        entity.setDescription(item.description());
        return mapper.toServiceCatalog(serviceCatalogRepository.save(entity));
    }

    /** 新增服务依赖边。 */
    @Transactional
    public DomainModels.ServiceDependency saveServiceDependency(DomainModels.ServiceDependency dependency) {
        ServiceDependencyEntity entity = new ServiceDependencyEntity();
        entity.setFromServiceCode(dependency.fromServiceCode());
        entity.setToServiceCode(dependency.toServiceCode());
        entity.setDependencyType(dependency.dependencyType());
        entity.setDependencyDesc(dependency.dependencyDesc());
        return mapper.toServiceDependency(serviceDependencyRepository.save(entity));
    }

    /**
     * 新建或更新 Agent 规划；结构化字段写入 plan_json。
     */
    @Transactional
    public DomainModels.AgentPlan saveAgentPlan(DomainModels.AgentPlan plan) {
        AgentPlanEntity entity = plan.id() == null
                ? new AgentPlanEntity()
                : agentPlanRepository.findById(plan.id()).orElseGet(AgentPlanEntity::new);
        entity.setUserId(plan.userId());
        entity.setRequirementTitle(plan.requirementTitle());
        entity.setRequirementContent(plan.requirementContent());
        entity.setStatus(plan.status());
        entity.setPlanJson(mapper.writePlanPayload(plan));
        if (plan.createdAt() != null) {
            entity.setCreatedAt(mapper.toLocal(plan.createdAt()));
        }
        return mapper.toAgentPlan(agentPlanRepository.save(entity));
    }

    /** 按 ID 查找规划。 */
    @Transactional(readOnly = true)
    public Optional<DomainModels.AgentPlan> findAgentPlan(Long planId) {
        return agentPlanRepository.findById(planId).map(mapper::toAgentPlan);
    }

    /** 列出用户规划（创建时间倒序）。 */
    @Transactional(readOnly = true)
    public List<DomainModels.AgentPlan> listAgentPlansByUser(Long userId) {
        return agentPlanRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(mapper::toAgentPlan)
                .toList();
    }

    /** 列出全部规划。 */
    @Transactional(readOnly = true)
    public List<DomainModels.AgentPlan> listAllAgentPlans() {
        return agentPlanRepository.findAll().stream().map(mapper::toAgentPlan).toList();
    }

    /** @return 用户总数（用于判断是否已种子化） */
    @Transactional(readOnly = true)
    public long countUsers() {
        return userRepository.count();
    }

    /**
     * 加载文档实体并附带按序号排序的切块。
     */
    private DomainModels.KnowledgeDocument loadDocument(KnowledgeDocumentEntity entity) {
        List<DomainModels.DocumentChunk> chunks = documentChunkRepository
                .findByDocumentIdOrderByChunkIndexAsc(entity.getId())
                .stream()
                .map(mapper::toChunk)
                .toList();
        return mapper.toDocument(entity, chunks);
    }
}
