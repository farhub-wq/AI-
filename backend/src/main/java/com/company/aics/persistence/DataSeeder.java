package com.company.aics.persistence;

import com.company.aics.domain.DomainModels;
import com.company.aics.rag.VectorIndexService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;

/**
 * 启动时 MySQL 演示数据种子：用户、双知识库、文档/切块、服务目录、示例会话与 Agent 规划。
 * 若已有用户则跳过写入，仅将现有文档重新 upsert 到向量索引。
 */
@Component
@Order(1)
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final int CHUNK_SIZE = 520;
    private static final int CHUNK_OVERLAP = 80;

    private final AppDataStore appDataStore;
    private final PasswordEncoder passwordEncoder;
    private final VectorIndexService vectorIndexService;

    /**
     * @param appDataStore       持久化门面
     * @param passwordEncoder    演示用户密码编码
     * @param vectorIndexService 向量索引同步
     */
    public DataSeeder(AppDataStore appDataStore, PasswordEncoder passwordEncoder, VectorIndexService vectorIndexService) {
        this.appDataStore = appDataStore;
        this.passwordEncoder = passwordEncoder;
        this.vectorIndexService = vectorIndexService;
    }

    /**
     * 应用启动回调：空库则全量种子，非空则仅重建向量索引。
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // 已有用户说明库已初始化，避免重复种子
        if (appDataStore.countUsers() > 0) {
            log.info("MySQL already seeded, ensuring demo admin role and indexing documents.");
            ensureDemoAdminRole();
            appDataStore.listAllDocuments().forEach(vectorIndexService::upsertDocument);
            return;
        }

        log.info("Seeding MySQL demo data...");
        // 演示账号为 ADMIN，可访问管理后台；也可用手机号 13800138000 登录
        DomainModels.User demoUser = appDataStore.saveUser(new DomainModels.User(
                null,
                "demo@qq.com",
                "13800138000",
                passwordEncoder.encode("Passw0rd!"),
                "演示用户",
                DomainModels.UserRole.ADMIN.name(),
                1,
                now().minusDays(3)
        ));

        DomainModels.KnowledgeBase supportKb = appDataStore.saveKnowledgeBase(new DomainModels.KnowledgeBase(
                null, "客服知识库", "customer_support", "产品介绍、常见问题与售后政策，用于智能客服问答。", now().minusDays(2)
        ));
        DomainModels.KnowledgeBase techKb = appDataStore.saveKnowledgeBase(new DomainModels.KnowledgeBase(
                null, "技术文档库", "technical_docs", "微服务接口与事件说明，供需求拆解 Agent 检索使用。", now().minusDays(2)
        ));

        // 客服库：从 classpath seed 资源切块入库
        DomainModels.KnowledgeDocument returnPolicy = saveDocFromResource(
                supportKb.id(), "退换货政策.txt", "policy", "policy", null, "seed/退换货政策.txt"
        );
        saveDocFromResource(supportKb.id(), "常见问题FAQ.md", "manual", "general", null, "seed/常见问题FAQ.md");
        saveDocFromResource(supportKb.id(), "公司产品介绍.txt", "manual", "general", null, "seed/公司产品介绍.txt");

        // 技术库：内联段落种子，并绑定 serviceCode
        saveDoc(techKb.id(), "订单服务接口.md", "api_spec", "engineering", "order-service", List.of(
                "订单服务负责创建订单、更新订单状态，并在支付成功后发布 order.created 事件。",
                "order.created 事件载荷包含 orderId、userId、totalAmount、createdAt 与支付状态。"
        ));
        saveDoc(techKb.id(), "通知服务事件.md", "event_spec", "engineering", "notification-service", List.of(
                "通知服务消费订单、营销与风控事件，并按模板发送短信或站内信。",
                "短信发送需要模板编码、收件人手机号，以及可用的通道配额。"
        ));
        saveDoc(techKb.id(), "用户服务资料.md", "service_spec", "engineering", "user-service", List.of(
                "用户服务管理资料、手机号、会员等级与认证字段。",
                "客服、营销与通知链路通过 userId 解析手机号。"
        ));
        saveDoc(techKb.id(), "商城前端结账页.md", "service_spec", "engineering", "mall-web", List.of(
                "商城前端负责结账流程、支付结果页与下单成功文案。",
                "成功页由后端订单状态与通知结果信号驱动展示。"
        ));

        appDataStore.saveServiceCatalog(new DomainModels.ServiceCatalogItem(null, "order-service", "订单服务", "backend", "交易中台", "创建订单、推进订单状态并发布订单事件"));
        appDataStore.saveServiceCatalog(new DomainModels.ServiceCatalogItem(null, "user-service", "用户服务", "backend", "用户中台", "存储资料、手机号与身份字段"));
        appDataStore.saveServiceCatalog(new DomainModels.ServiceCatalogItem(null, "notification-service", "通知服务", "backend", "消息平台", "发送短信、站内信与模板消息"));
        appDataStore.saveServiceCatalog(new DomainModels.ServiceCatalogItem(null, "mall-web", "商城前端", "frontend", "店铺前端", "负责结账、支付状态与成功页体验"));

        appDataStore.saveServiceDependency(new DomainModels.ServiceDependency(null, "order-service", "notification-service", "event", "下单成功事件由通知服务消费"));
        appDataStore.saveServiceDependency(new DomainModels.ServiceDependency(null, "user-service", "notification-service", "data", "通知发送依赖用户手机号"));
        appDataStore.saveServiceDependency(new DomainModels.ServiceDependency(null, "order-service", "mall-web", "api", "前端成功页读取后端订单状态"));
        appDataStore.saveServiceDependency(new DomainModels.ServiceDependency(null, "notification-service", "mall-web", "config", "通知结果会影响前端状态文案"));

        DomainModels.Conversation conversation = appDataStore.saveConversation(new DomainModels.Conversation(
                null, demoUser.id(), supportKb.id(), "退货时限咨询", "售后问题", now().minusHours(4), now().minusHours(4).plusMinutes(2)
        ));
        DomainModels.Message userMessage = appDataStore.saveMessage(new DomainModels.Message(
                null, conversation.id(), demoUser.id(), DomainModels.MessageRole.USER,
                "退货有时间限制吗？", List.of(), null, null, 0, 0.0, 0, "trace-demo-user", now().minusHours(4)
        ));
        DomainModels.Message assistantMessage = appDataStore.saveMessage(new DomainModels.Message(
                null, conversation.id(), demoUser.id(), DomainModels.MessageRole.ASSISTANT,
                "根据当前知识库，签收后 7 天内且商品保持可二次销售状态，支持无理由退货。",
                List.of(new DomainModels.Citation(returnPolicy.id(), "退换货政策.txt", returnPolicy.chunks().getFirst().vectorId(),
                        "签收之日起 7 个自然日内，商品完好可二次销售的，可申请七天无理由退货。")),
                "售后问题", "success", 2, 0.92, 120, "trace-demo-assistant", now().minusHours(4).plusSeconds(3)
        ));
        appDataStore.saveFeedback(new DomainModels.MessageFeedback(
                null, assistantMessage.id(), demoUser.id(), 1, "helpful", "回答清楚，引用也相关。", now().minusHours(3)
        ));

        DomainModels.AgentPlan plan = new DomainModels.AgentPlan(
                null, demoUser.id(),
                "下单成功后自动发送短信",
                "用户下单完成后，系统应自动向用户手机号发送短信，并在前端成功页展示发送结果。",
                "success",
                List.of(
                        new DomainModels.ImpactedService("order-service", "订单服务", "负责输出下单成功事件"),
                        new DomainModels.ImpactedService("user-service", "用户服务", "提供收件人手机号"),
                        new DomainModels.ImpactedService("notification-service", "通知服务", "消费事件并发送短信"),
                        new DomainModels.ImpactedService("mall-web", "商城前端", "在成功页展示最终结果")
                ),
                List.of(List.of("开放并校验手机号查询", "更新前端成功状态文案")),
                List.of(
                        new DomainModels.AgentTask(1L, "定义订单成功事件载荷", "order-service", "serial", List.of(), "上游订单契约应先稳定，再启动下游改造。", "交易中台", null),
                        new DomainModels.AgentTask(2L, "开放并校验手机号查询", "user-service", "parallel", List.of(), "发送链路必须能获取用户手机号。", "用户中台", "data"),
                        new DomainModels.AgentTask(3L, "实现短信通知消费流程", "notification-service", "serial", List.of(1L, 2L), "通知发送依赖上游事件载荷与收件人数据。", "触达中台", "event"),
                        new DomainModels.AgentTask(4L, "更新前端成功状态文案", "mall-web", "parallel", List.of(), "前端文案与状态展示通常可并行推进。", "商城前端", "config"),
                        new DomainModels.AgentTask(5L, "联调与端到端验收", "mall-web", "serial", List.of(1L, 2L, 3L, 4L), "统一验证改动服务、依赖顺序、通知送达与界面表现。", "平台联调", "api")
                ),
                List.of(
                        "验证订单服务已发布或暴露更新后的契约（如 order.created）。",
                        "验证通知服务能消费事件并完成短信发送。",
                        "验证手机号查询与校验规则可用。",
                        "验证前端成功页展示预期结果文案。"
                ),
                List.of(),
                now().minusHours(2),
                "CHG-DEMO-001",
                "P1",
                "演示用户",
                List.of(
                        new DomainModels.AgentEvidenceHit("订单服务-下单接口.md", "order-service", 0.9),
                        new DomainModels.AgentEvidenceHit("通知服务-短信事件.md", "notification-service", 0.88)
                ),
                List.of(
                        new DomainModels.ServiceDependency(null, "order-service", "notification-service", "event", "下单成功事件驱动短信"),
                        new DomainModels.ServiceDependency(null, "user-service", "notification-service", "data", "通知需要用户手机号"),
                        new DomainModels.ServiceDependency(null, "order-service", "mall-web", "api", "前端展示下单结果")
                ),
                List.of("order-service", "user-service", "notification-service", "mall-web"),
                List.of(
                        "确认影响面服务列表与业务方/架构师对齐（共 4 个服务）。",
                        "复核依赖边是否完整（本计划引用 3 条依赖）。",
                        "按「建议发布顺序」安排合并与灰度，禁止下游先于上游合入强依赖契约。",
                        "准备回滚点：事件契约、短信开关、前端文案开关。",
                        "联调通过后再关闭变更单（含端到端验收任务）。"
                )
        );
        appDataStore.saveAgentPlan(plan);

        // 全量文档写入向量索引，供 RAG 立即可用
        appDataStore.listAllDocuments().forEach(vectorIndexService::upsertDocument);
        int supportChars = appDataStore.listDocumentsByKb(supportKb.id()).stream()
                .flatMap(doc -> doc.chunks().stream())
                .mapToInt(chunk -> chunk.content().length())
                .sum();
        log.info("MySQL seed completed. demoUserId={}, supportKbId={}, techKbId={}, conversationId={}, userMessageId={}, supportSeedChars={}",
                demoUser.id(), supportKb.id(), techKb.id(), conversation.id(), userMessage.id(), supportChars);
    }

    /**
     * 已有库升级：确保演示账号具备 ADMIN，便于访问管理后台（普通注册用户仍为 USER）。
     */
    private void ensureDemoAdminRole() {
        appDataStore.findUserByAccount("demo@qq.com").ifPresent(user -> {
            if (DomainModels.UserRole.ADMIN.name().equalsIgnoreCase(user.role())) {
                return;
            }
            appDataStore.saveUser(new DomainModels.User(
                    user.id(),
                    user.email(),
                    user.phone(),
                    user.passwordHash(),
                    user.displayName(),
                    DomainModels.UserRole.ADMIN.name(),
                    user.status(),
                    user.createdAt()
            ));
            log.info("Upgraded demo@qq.com role to ADMIN.");
        });
    }

    /**
     * 从 classpath 读取文本、切块后保存为知识文档。
     */
    private DomainModels.KnowledgeDocument saveDocFromResource(
            Long kbId,
            String fileName,
            String docType,
            String priority,
            String serviceCode,
            String classpathLocation
    ) {
        String content = readClasspathText(classpathLocation);
        List<String> chunks = splitText(content, CHUNK_SIZE, CHUNK_OVERLAP);
        if (chunks.isEmpty()) {
            chunks = List.of(content.trim());
        }
        return saveDoc(kbId, fileName, docType, priority, serviceCode, chunks);
    }

    /** 读取 classpath 文本资源为 UTF-8 字符串。 */
    private String readClasspathText(String location) {
        try {
            ClassPathResource resource = new ClassPathResource(location);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8).trim();
        } catch (IOException ex) {
            throw new IllegalStateException("无法读取种子文档: " + location, ex);
        }
    }

    /**
     * 按固定窗口切分文本，优先在换行/句号处断开，并保留 overlap 重叠。
     */
    private List<String> splitText(String text, int chunkSize, int overlap) {
        String normalized = text.replace("\r\n", "\n").trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(normalized.length(), start + chunkSize);
            if (end < normalized.length()) {
                // 尽量在自然断句处切开，避免半句截断
                int breakAt = Math.max(
                        normalized.lastIndexOf('\n', end),
                        Math.max(normalized.lastIndexOf('。', end), normalized.lastIndexOf('；', end))
                );
                if (breakAt > start + chunkSize / 2) {
                    end = breakAt + 1;
                }
            }
            String piece = normalized.substring(start, end).trim();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }
            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(0, end - overlap);
        }
        return chunks;
    }

    /**
     * 两阶段保存文档：先落空切块占位拿 ID，再写入完整切块列表。
     */
    private DomainModels.KnowledgeDocument saveDoc(
            Long kbId,
            String fileName,
            String docType,
            String priority,
            String serviceCode,
            List<String> contents
    ) {
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1) : "txt";
        // 先保存元数据以获得稳定 documentId，再挂切块
        DomainModels.KnowledgeDocument placeholder = appDataStore.saveDocument(new DomainModels.KnowledgeDocument(
                null, kbId, fileName, ext, docType,
                Integer.toHexString(String.join("|", contents).hashCode()),
                "ready", priority, serviceCode, List.of(), now().minusDays(1)
        ));

        List<DomainModels.DocumentChunk> chunks = new ArrayList<>();
        for (int i = 0; i < contents.size(); i++) {
            chunks.add(new DomainModels.DocumentChunk(
                    null,
                    placeholder.id(),
                    kbId,
                    "doc" + placeholder.id() + "-chunk" + (i + 1),
                    i + 1,
                    i == 0 ? "概述" : "细则",
                    priority,
                    contents.get(i),
                    Map.of(
                            "document_name", fileName,
                            "service_code", serviceCode == null ? "" : serviceCode,
                            "priority", priority,
                            "chunk_index", i + 1
                    )
            ));
        }
        return appDataStore.saveDocument(new DomainModels.KnowledgeDocument(
                placeholder.id(), placeholder.kbId(), placeholder.fileName(), placeholder.fileExt(), placeholder.docType(),
                placeholder.contentHash(), placeholder.status(), placeholder.priority(), placeholder.serviceCode(),
                chunks, placeholder.uploadedAt()
        ));
    }

    /** @return 东八区当前时间 */
    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.ofHours(8));
    }
}
