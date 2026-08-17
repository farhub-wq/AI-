package com.company.aics.rag;

import com.company.aics.application.ConversationService;
import com.company.aics.application.DailyQuestionLimitExceededException;
import com.company.aics.application.KnowledgeBaseService;
import com.company.aics.config.AiProperties;
import com.company.aics.domain.DomainModels;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAG 流式问答服务：日限流、检索重排、拼装上下文，经 SSE 推送 token/引用/结束事件。
 * 无证据时走本地兜底；一致性校验失败时发 {@code answer_replace} 整段替换；追问优先 LLM 生成。
 * <p>
 * {@code answerStatus} 语义（供管理看板与会话气泡）：
 * {@code streaming} 生成中；{@code success} 正常；{@code fallback} 无证据兜底；
 * {@code degraded} LLM 失败/空答/一致性校验失败后的降级回答。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ConversationService conversationService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AiProperties aiProperties;
    private final TaskExecutor streamingTaskExecutor;
    private final OpenAiCompatibleChatClient chatClient;
    private final IntentClassifier intentClassifier;
    private final EvidenceGovernanceService evidenceGovernanceService;

    /**
     * @param conversationService        会话与消息服务
     * @param knowledgeBaseService       知识检索服务
     * @param aiProperties               RAG/限流配置
     * @param streamingTaskExecutor      SSE 异步执行器
     * @param chatClient                 OpenAI 兼容聊天客户端
     * @param intentClassifier           提问意图分类器
     * @param evidenceGovernanceService  大规模证据分层与一致性校验
     */
    public ChatService(
            ConversationService conversationService,
            KnowledgeBaseService knowledgeBaseService,
            AiProperties aiProperties,
            TaskExecutor streamingTaskExecutor,
            OpenAiCompatibleChatClient chatClient,
            IntentClassifier intentClassifier,
            EvidenceGovernanceService evidenceGovernanceService
    ) {
        this.conversationService = conversationService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.aiProperties = aiProperties;
        this.streamingTaskExecutor = streamingTaskExecutor;
        this.chatClient = chatClient;
        this.intentClassifier = intentClassifier;
        this.evidenceGovernanceService = evidenceGovernanceService;
    }

    /**
     * 启动一轮流式问答：落库用户消息与占位助手消息，异步检索生成并通过 SSE 推送。
     *
     * @return 永不超时的 {@link SseEmitter}（超时由客户端断开控制）
     */
    public SseEmitter streamChat(Long userId, Long conversationId, Long kbId, String question, Integer historyRounds) {
        enforceDailyQuestionLimit(userId);

        long startedAt = System.currentTimeMillis();
        // kbId 为空或 ≤0：跨客服知识库自动路由到最高分库
        boolean autoRoute = kbId == null || kbId <= 0;
        Long resolvedKbId = autoRoute
                ? knowledgeBaseService.resolveBestKnowledgeBaseId(question, aiProperties.getRagTopK())
                : kbId;

        DomainModels.Conversation conversation = conversationService.ensureConversation(
                userId, conversationId, resolvedKbId, question
        );
        // 已有会话在自动路由模式下，按本题重新绑定最相关知识库
        if (autoRoute && !Objects.equals(conversation.kbId(), resolvedKbId)) {
            conversation = conversationService.updateConversationKb(userId, conversation.id(), resolvedKbId);
        }
        final DomainModels.Conversation boundConversation = conversation;
        final Long routedKbId = boundConversation.kbId();

        String traceId = UUID.randomUUID().toString().replace("-", "");
        conversationService.addUserMessage(boundConversation.id(), userId, question, traceId);

        // 意图识别：LLM 优先（含重试），失败降级规则；闲聊不灌知识库
        IntentClassifier.IntentResult intent = intentClassifier.classify(question);
        String intentLabel = intent.label();
        log.info("Intent classified traceId={} label={} score={} signals={}",
                traceId, intentLabel, intent.score(), intent.matchedSignals());

        EvidenceGovernanceService.EvidenceBundle evidenceBundle;
        List<DomainModels.Citation> citations;
        if (IntentClassifier.CHITCHAT.equals(intentLabel)) {
            // 闲聊：跳过向量检索，空证据交给闲聊 Prompt / 本地短回复
            evidenceBundle = new EvidenceGovernanceService.EvidenceBundle(
                    List.of(), List.of(), List.of(), List.of(), "skipped-for-chitchat"
            );
            citations = List.of();
        } else {
            List<KnowledgeBaseService.SearchHit> hits = knowledgeBaseService.searchSupportChunks(
                    boundConversation.kbId(),
                    question,
                    aiProperties.getRagTopK()
            );
            List<KnowledgeBaseService.SearchHit> thresholdHits = filterByThreshold(hits);
            evidenceBundle = evidenceGovernanceService.pack(
                    thresholdHits,
                    question,
                    aiProperties.getRagMaxContextChars()
            );
            log.info("Evidence packing traceId={} {}", traceId, evidenceBundle.packingNote());
            citations = evidenceBundle.citationHits().stream()
                    .map(hit -> new DomainModels.Citation(
                            hit.document().id(),
                            hit.document().fileName(),
                            hit.chunk().vectorId(),
                            summarizeSnippet(hit.chunk().content())
                    ))
                    .toList();
        }

        List<DomainModels.Message> history = conversationService.listRecentMessages(
                boundConversation.id(),
                sanitizeHistoryRounds(historyRounds)
        );

        double topScore = evidenceBundle.citationHits().isEmpty()
                ? 0.0
                : roundScore(evidenceBundle.citationHits().getFirst().score());
        DomainModels.Message assistantMessage = conversationService.addAssistantMessage(
                boundConversation.id(),
                userId,
                "",
                citations,
                intentLabel,
                "streaming",
                evidenceBundle.citationHits().size(),
                topScore,
                0,
                traceId
        );

        SseEmitter emitter = new SseEmitter(0L);
        streamingTaskExecutor.execute(() -> {
            StringBuilder answerBuilder = new StringBuilder();
            String answerStatus = "success";
            try {
                sendEvent(emitter, "message_start", Map.of(
                        "messageId", assistantMessage.id(),
                        "conversationId", boundConversation.id(),
                        "kbId", routedKbId,
                        "traceId", traceId,
                        "intentLabel", intentLabel,
                        "evidencePacking", evidenceBundle.packingNote()
                ));

                if (IntentClassifier.CHITCHAT.equals(intentLabel)) {
                    // 闲聊：走 LLM 闲聊 Prompt；失败则用本地短回复（不走知识库兜底话术）
                    try {
                        chatClient.streamAnswer(question, history, evidenceBundle, intentLabel, delta -> {
                            answerBuilder.append(delta);
                            try {
                                sendEvent(emitter, "token", Map.of("delta", delta));
                            } catch (IOException ex) {
                                throw new IllegalStateException("SSE token 发送失败。", ex);
                            }
                        });
                        if (!StringUtils.hasText(answerBuilder.toString())) {
                            answerStatus = "degraded";
                            streamLocalText(emitter, answerBuilder, buildChitchatFallback(question));
                        }
                    } catch (Exception ex) {
                        log.warn("闲聊 LLM 调用在重试后仍失败，使用本地闲聊回复。", ex);
                        answerStatus = "degraded";
                        streamLocalText(emitter, answerBuilder, buildChitchatFallback(question));
                    }
                } else if (evidenceBundle.isEmpty()) {
                    answerStatus = "fallback";
                    streamLocalText(emitter, answerBuilder, buildNoEvidenceFallback());
                } else {
                    try {
                        chatClient.streamAnswer(question, history, evidenceBundle, intentLabel, delta -> {
                            answerBuilder.append(delta);
                            try {
                                sendEvent(emitter, "token", Map.of("delta", delta));
                            } catch (IOException ex) {
                                throw new IllegalStateException("SSE token 发送失败。", ex);
                            }
                        });
                        if (!StringUtils.hasText(answerBuilder.toString())) {
                            answerStatus = "degraded";
                            streamLocalText(emitter, answerBuilder, buildEvidenceFallback(evidenceBundle));
                        } else {
                            // 分步校验：回答中的政策类数字须能在证据中找到，否则整段替换为证据摘要
                            EvidenceGovernanceService.ConsistencyCheck check =
                                    evidenceGovernanceService.validateAnswer(answerBuilder.toString(), evidenceBundle);
                            if (!check.passed()) {
                                log.warn("Answer consistency check failed traceId={} reason={}", traceId, check.reason());
                                answerStatus = "degraded";
                                replaceAndStreamLocalText(
                                        emitter,
                                        answerBuilder,
                                        buildEvidenceFallback(evidenceBundle)
                                                + "\n\n（系统校验：模型回答含证据外政策数字，已改为仅基于知识库摘要回复。）",
                                        check.reason()
                                );
                            }
                        }
                    } catch (Exception ex) {
                        log.warn("LLM 流式调用在重试后仍失败，回退到证据摘要回答。", ex);
                        answerStatus = "degraded";
                        replaceAndStreamLocalText(
                                emitter,
                                answerBuilder,
                                buildEvidenceFallback(evidenceBundle),
                                "llm-stream-failed"
                        );
                    }
                }

                if (!citations.isEmpty()) {
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (DomainModels.Citation citation : citations) {
                        items.add(Map.of(
                                "documentId", citation.documentId(),
                                "documentName", citation.documentName(),
                                "chunkId", citation.chunkId(),
                                "snippet", citation.snippet()
                        ));
                    }
                    sendEvent(emitter, "citation", Map.of("items", items));
                }

                List<String> followUps = buildFollowUpSuggestions(
                        intentLabel,
                        question,
                        answerBuilder.toString().trim()
                );
                int latencyMs = (int) Math.max(1, System.currentTimeMillis() - startedAt);
                conversationService.updateAssistantMessage(
                        assistantMessage.id(),
                        answerBuilder.toString().trim(),
                        citations,
                        intentLabel,
                        answerStatus,
                        evidenceBundle.citationHits().size(),
                        topScore,
                        latencyMs
                );

                sendEvent(emitter, "message_end", Map.of(
                        "messageId", assistantMessage.id(),
                        "answerStatus", answerStatus,
                        "intentLabel", intentLabel,
                        "kbId", routedKbId,
                        "followUpSuggestions", followUps,
                        "traceId", traceId,
                        "evidencePacking", evidenceBundle.packingNote()
                ));
                emitter.complete();
            } catch (Exception ex) {
                try {
                    sendEvent(emitter, "error", Map.of(
                            "code", "STREAM_FAILED",
                            "message", ex.getMessage() == null ? "流式输出失败" : ex.getMessage(),
                            "traceId", traceId
                    ));
                } catch (IOException ignored) {
                    // 二次发送失败时忽略，避免掩盖原始异常
                }
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }

    /**
     * 将本地兜底文案按小段推送为 SSE token，模拟流式体验。
     */
    private void streamLocalText(SseEmitter emitter, StringBuilder answerBuilder, String text) throws IOException {
        for (String part : splitForStreaming(text)) {
            answerBuilder.append(part);
            sendEvent(emitter, "token", Map.of("delta", part));
            sleepQuietly(25L);
        }
    }

    /**
     * 先发 {@code answer_replace} 清空前端已渲染草稿，再流式推送替换正文。
     * 用于一致性校验失败或 LLM 中途失败后的整段降级，避免「错误答案 + 兜底」叠字。
     */
    private void replaceAndStreamLocalText(
            SseEmitter emitter,
            StringBuilder answerBuilder,
            String text,
            String reason
    ) throws IOException {
        answerBuilder.setLength(0);
        sendEvent(emitter, "answer_replace", Map.of(
                "content", "",
                "reason", reason == null ? "" : reason
        ));
        streamLocalText(emitter, answerBuilder, text);
    }

    /**
     * 校验当日提问次数是否超过配置上限。
     */
    private void enforceDailyQuestionLimit(Long userId) {
        long currentCount = conversationService.countUserQuestionsToday(userId);
        if (currentCount >= aiProperties.getDailyQuestionLimit()) {
            throw new DailyQuestionLimitExceededException(
                    "已达到每日提问上限（" + aiProperties.getDailyQuestionLimit() + " 次/天）。"
            );
        }
    }

    /**
     * 仅做阈值过滤；分层预算与政策优先由 {@link EvidenceGovernanceService} 负责。
     * 低于阈值的命中一律丢弃，保证「无足够证据 → 仅兜底话术」。
     */
    private List<KnowledgeBaseService.SearchHit> filterByThreshold(List<KnowledgeBaseService.SearchHit> hits) {
        return hits.stream()
                .filter(hit -> hit.score() >= aiProperties.getRagScoreThreshold())
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .toList();
    }

    /** 将历史轮数限制在 1–10，缺省 3。 */
    private int sanitizeHistoryRounds(Integer historyRounds) {
        if (historyRounds == null) {
            return 3;
        }
        return Math.max(1, Math.min(10, historyRounds));
    }

    /** 无检索证据时的用户提示文案（业务意图）。 */
    private String buildNoEvidenceFallback() {
        return """
                当前知识库中没有找到足够依据来回答该问题。
                请补充更多细节，例如：商品名称、订单状态、售后场景或具体报错信息，我会再帮你查询。
                """;
    }

    /**
     * 闲聊意图本地兜底：不编造天气等外部信息，也不硬扯商品证据。
     */
    private String buildChitchatFallback(String question) {
        String q = question == null ? "" : question.trim();
        if (q.contains("天气") || q.toLowerCase(java.util.Locale.ROOT).contains("weather")) {
            return "我这边是购物客服，暂时查不到实时天气信息哦。"
                    + "如果你想了解商品发货、规格或售后政策，我可以马上帮你查知识库。";
        }
        return "哈哈，我更擅长解答购物相关问题～"
                + "你可以问我发货时效、商品规格，或退换货政策，我来帮你查。";
    }

    /** LLM 失败或一致性校验失败时，按分层证据拼装保守摘要回答。 */
    private String buildEvidenceFallback(EvidenceGovernanceService.EvidenceBundle bundle) {
        StringBuilder sb = new StringBuilder("根据当前知识库资料（保守摘要）：\n");
        for (EvidenceGovernanceService.LayeredEvidence item : bundle.allLayers()) {
            sb.append("- ")
                    .append(item.evidenceId())
                    .append("（")
                    .append(item.layer())
                    .append("）")
                    .append(summarizeSnippet(item.displayText()))
                    .append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 优先用 LLM 基于当轮问答生成 2–3 条追问；失败则按意图回退模板。
     */
    private List<String> buildFollowUpSuggestions(String intentLabel, String question, String answer) {
        try {
            List<String> fromLlm = chatClient.suggestFollowUps(question, answer, intentLabel);
            if (fromLlm != null && fromLlm.size() >= 2) {
                return fromLlm.stream().limit(3).toList();
            }
        } catch (Exception ex) {
            log.warn("LLM follow-up suggestions failed, fallback to templates: {}", ex.getMessage());
        }
        return templateFollowUps(intentLabel, question);
    }

    /** 意图模板追问（LLM 不可用时的兜底）。 */
    private List<String> templateFollowUps(String intentLabel, String question) {
        if (IntentClassifier.AFTER_SALES.equals(intentLabel)
                || question.contains("退货") || question.contains("退款")) {
            return List.of("退货运费由谁承担？", "哪些商品不支持无理由退货？", "退款多久到账？");
        }
        if (IntentClassifier.COMPLAINT.equals(intentLabel)) {
            return List.of("需要帮你升级到人工客服吗？", "方便提供订单号便于核查吗？", "希望怎么处理比较合适？");
        }
        if (IntentClassifier.CHITCHAT.equals(intentLabel)) {
            return List.of("想了解退换货政策吗？", "需要查一下发货时效吗？", "有具体订单问题可以问我。");
        }
        if (IntentClassifier.PRODUCT.equals(intentLabel)
                || question.contains("发货") || question.contains("物流")) {
            return List.of("偏远地区多久能到？", "节假日发货时效会变化吗？", "如何查看物流轨迹？");
        }
        return List.of("能否结合当前订单状态说明？", "需要我给出逐步处理建议吗？", "还想了解相关售后政策吗？");
    }

    /** 引用片段截断到约 120 字。 */
    private String summarizeSnippet(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String compact = content.replaceAll("\\s+", " ").trim();
        return compact.length() > 120 ? compact.substring(0, 120) + "..." : compact;
    }

    /** 将文本拆成约 12 字一段，便于本地流式推送。 */
    private List<String> splitForStreaming(String text) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + 12);
            parts.add(text.substring(start, end));
            start = end;
        }
        return parts;
    }

    /** 发送命名 SSE 事件。 */
    private void sendEvent(SseEmitter emitter, String eventName, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(eventName).data(data));
    }

    /** 静默休眠；中断时恢复中断标志。 */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** 分数保留两位小数。 */
    private double roundScore(double score) {
        return Math.round(score * 100.0) / 100.0;
    }
}
