package com.company.aics.application;

import com.company.aics.api.ApiModels;
import com.company.aics.domain.DomainModels;
import com.company.aics.persistence.AppDataStore;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * 管理端应用服务：聚合提问量、反馈、回退率、Agent 规划成功率及全站会话列表。
 * 统计基于 MySQL（{@link AppDataStore}）中的消息、反馈与规划数据。
 */
@Service
public class AdminService {

    private final AppDataStore appDataStore;

    /**
     * @param appDataStore MySQL 数据访问门面
     */
    public AdminService(AppDataStore appDataStore) {
        this.appDataStore = appDataStore;
    }

    /**
     * 计算运营总览指标（当日用户提问、助手消息数、反馈与各类比率）。
     */
    public ApiModels.MetricsOverviewView getOverviewMetrics() {
        LocalDate today = now().toLocalDate();
        List<DomainModels.Message> allMessages = appDataStore.listAllMessages();
        List<DomainModels.MessageFeedback> allFeedback = appDataStore.listAllFeedback();
        List<DomainModels.AgentPlan> allPlans = appDataStore.listAllAgentPlans();

        long dailyQuestionCount = allMessages.stream()
                .filter(message -> message.role() == DomainModels.MessageRole.USER)
                .filter(message -> Objects.equals(message.createdAt().toLocalDate(), today))
                .count();

        long assistantMessageCount = allMessages.stream()
                .filter(message -> message.role() == DomainModels.MessageRole.ASSISTANT)
                .count();
        long feedbackCount = allFeedback.size();
        long positiveCount = allFeedback.stream()
                .filter(feedback -> feedback.rating() > 0)
                .count();
        // fallback 状态表示检索不足或模型走了兜底回答
        long fallbackCount = allMessages.stream()
                .filter(message -> message.role() == DomainModels.MessageRole.ASSISTANT)
                .filter(message -> "fallback".equalsIgnoreCase(message.answerStatus()))
                .count();
        long successPlanCount = allPlans.stream()
                .filter(plan -> "success".equalsIgnoreCase(plan.status()))
                .count();
        long totalPlanCount = allPlans.size();

        return new ApiModels.MetricsOverviewView(
                dailyQuestionCount,
                assistantMessageCount,
                feedbackCount,
                safeRate(positiveCount, feedbackCount),
                safeRate(fallbackCount, assistantMessageCount),
                safeRate(successPlanCount, totalPlanCount)
        );
    }

    /**
     * 按日聚合用户提问量趋势；窗口限制在 1–30 天。
     */
    public List<ApiModels.DailyQuestionPointView> getDailyQuestionTrend(int days) {
        int window = Math.max(1, Math.min(30, days));
        LocalDate end = now().toLocalDate();
        LocalDate start = end.minusDays(window - 1L);
        // TreeMap 保证日期升序输出
        Map<LocalDate, Long> counter = new TreeMap<>();
        for (LocalDate cursor = start; !cursor.isAfter(end); cursor = cursor.plusDays(1)) {
            counter.put(cursor, 0L);
        }

        appDataStore.listAllMessages().stream()
                .filter(message -> message.role() == DomainModels.MessageRole.USER)
                .forEach(message -> {
                    LocalDate day = message.createdAt().toLocalDate();
                    if (!day.isBefore(start) && !day.isAfter(end)) {
                        counter.merge(day, 1L, Long::sum);
                    }
                });

        List<ApiModels.DailyQuestionPointView> points = new ArrayList<>();
        counter.forEach((date, count) -> points.add(new ApiModels.DailyQuestionPointView(date.toString(), count)));
        return points;
    }

    /**
     * 汇总正/负反馈，并列出近期低分问题（含问答预览）。
     */
    public ApiModels.FeedbackMetricsView getFeedbackMetrics() {
        List<DomainModels.MessageFeedback> allFeedback = appDataStore.listAllFeedback();
        List<ApiModels.FeedbackIssueView> lowRatingIssues = allFeedback.stream()
                .filter(feedback -> feedback.rating() < 0)
                .sorted(Comparator.comparing(DomainModels.MessageFeedback::createdAt).reversed())
                .map(feedback -> {
                    DomainModels.Message answerMessage = appDataStore.findMessage(feedback.messageId()).orElse(null);
                    if (answerMessage == null) {
                        return null;
                    }
                    List<DomainModels.Message> conversationMessages =
                            appDataStore.listMessagesByConversation(answerMessage.conversationId());
                    // 取该会话中最后一条用户问题作为预览上下文
                    Optional<DomainModels.Message> questionMessage = conversationMessages.stream()
                            .filter(message -> message.role() == DomainModels.MessageRole.USER)
                            .reduce((first, second) -> second);
                    String questionPreview = questionMessage.map(DomainModels.Message::content).orElse("");
                    if (questionPreview.length() > 30) {
                        questionPreview = questionPreview.substring(0, 30);
                    }
                    String answerPreview = answerMessage.content();
                    if (answerPreview.length() > 40) {
                        answerPreview = answerPreview.substring(0, 40);
                    }
                    return new ApiModels.FeedbackIssueView(
                            answerMessage.id(),
                            answerMessage.conversationId(),
                            questionPreview,
                            answerPreview,
                            feedback.reasonCode(),
                            feedback.comment(),
                            feedback.createdAt()
                    );
                })
                .filter(Objects::nonNull)
                .toList();

        long positiveCount = allFeedback.stream()
                .filter(feedback -> feedback.rating() > 0)
                .count();
        long negativeCount = allFeedback.stream()
                .filter(feedback -> feedback.rating() < 0)
                .count();
        return new ApiModels.FeedbackMetricsView(positiveCount, negativeCount, lowRatingIssues);
    }

    /**
     * 分页列出全站会话，附带用户展示名、知识库名与末条预览。
     */
    public List<ApiModels.AdminConversationView> listAllConversations(int page, int pageSize) {
        return appDataStore.listAllConversations().stream()
                .skip((long) Math.max(page - 1, 0) * pageSize)
                .limit(pageSize)
                .map(conversation -> {
                    DomainModels.User user = appDataStore.findUserById(conversation.userId()).orElse(null);
                    List<DomainModels.Message> messages = appDataStore.listMessagesByConversation(conversation.id());
                    String lastMessagePreview = messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
                    if (lastMessagePreview.length() > 40) {
                        lastMessagePreview = lastMessagePreview.substring(0, 40);
                    }
                    DomainModels.KnowledgeBase kb = appDataStore.findKnowledgeBase(conversation.kbId()).orElse(null);
                    return new ApiModels.AdminConversationView(
                            conversation.id(),
                            conversation.title(),
                            user == null ? "未知用户" : user.displayName(),
                            kb == null ? "未知知识库" : kb.name(),
                            lastMessagePreview,
                            conversation.updatedAt()
                    );
                })
                .toList();
    }

    /**
     * 安全除法得到比率，分母为 0 时返回 0，并保留四位小数。
     */
    private double safeRate(long numerator, long denominator) {
        if (denominator == 0) {
            return 0.0;
        }
        return Math.round((numerator * 10000.0) / denominator) / 10000.0;
    }

    /** @return 东八区当前时间 */
    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.ofHours(8));
    }
}
