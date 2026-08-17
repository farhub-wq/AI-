package com.company.aics.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 客服意图分类器：优先用 LLM 智能识别四类意图，识别失败或重试耗尽后降级到关键词规则。
 * 标签对齐 README：产品咨询 / 售后问题 / 闲聊 / 投诉。
 */
@Component
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    /** README 要求的四类意图。 */
    public static final String PRODUCT = "产品咨询";
    public static final String AFTER_SALES = "售后问题";
    public static final String CHITCHAT = "闲聊";
    public static final String COMPLAINT = "投诉";

    private static final Pattern OFF_TOPIC_CHITCHAT = Pattern.compile(
            "(天气|气温|下雨|下雪|几度|预报|"
                    + "几点|什么时候了|现在时间|"
                    + "笑话|唱歌|讲故事|"
                    + "你吃饭|吃了吗|在干嘛|干什么呢|"
                    + "地球|宇宙|明星|足球|篮球|"
                    + "weather|joke|time)",
            Pattern.CASE_INSENSITIVE
    );

    private final OpenAiCompatibleChatClient chatClient;

    /**
     * @param chatClient LLM 客户端（{@code @Lazy} 打破与 ChatClient 的循环依赖）
     */
    public IntentClassifier(@Lazy OpenAiCompatibleChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /** 分类结果：标签、置信分、触发命中摘要（便于观测与调试）。 */
    public record IntentResult(String label, double score, String matchedSignals) {
    }

    /**
     * 对用户问题做意图分类：LLM 优先（内置超时/限流重试）→ 解析失败或调用失败则关键词规则降级。
     */
    public IntentResult classify(String question) {
        if (!StringUtils.hasText(question)) {
            return new IntentResult(CHITCHAT, 0.0, "empty");
        }

        String text = question.trim();
        try {
            String raw = chatClient.classifyCustomerIntent(text);
            String label = parseLlmLabel(raw);
            if (label != null) {
                // 固定高置信分：表示「LLM 成功解析」，与规则路径的 /3.0 置信度不可直接横向比较
                return new IntentResult(label, 0.92, "llm:" + abbreviate(raw));
            }
            log.warn("LLM intent response unparseable, fallback to rules. raw={}", abbreviate(raw));
        } catch (Exception ex) {
            log.warn("LLM intent classification failed after retries, fallback to rules: {}", ex.getMessage());
        }

        IntentResult rules = classifyByRules(text);
        return new IntentResult(
                rules.label(),
                rules.score(),
                "rules-fallback:" + rules.matchedSignals()
        );
    }

    /**
     * 将 LLM 输出归一为四类标签之一；无法识别返回 null。
     */
    static String parseLlmLabel(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String text = raw.trim()
                .replace("\"", "")
                .replace("'", "")
                .replace("。", "")
                .replace(".", "")
                .replace("：", "")
                .replace(":", "");
        // 去掉常见前缀
        text = text.replaceFirst("(?i)^(意图|标签|分类|intent|label)\\s*", "").trim();
        if (text.contains("\n")) {
            text = text.lines().map(String::trim).filter(StringUtils::hasText).findFirst().orElse(text);
        }

        if (PRODUCT.equals(text) || AFTER_SALES.equals(text) || CHITCHAT.equals(text) || COMPLAINT.equals(text)) {
            return text;
        }

        String lowered = text.toLowerCase(Locale.ROOT);
        if (text.contains("投诉") || lowered.contains("complaint")) {
            return COMPLAINT;
        }
        if (text.contains("售后") || text.contains("退货") || text.contains("退款")
                || lowered.contains("after") || lowered.contains("refund")) {
            return AFTER_SALES;
        }
        if (text.contains("闲聊") || text.contains("寒暄") || lowered.contains("chitchat")
                || lowered.contains("small talk") || lowered.contains("greeting")) {
            return CHITCHAT;
        }
        if (text.contains("产品") || text.contains("咨询") || lowered.contains("product")) {
            return PRODUCT;
        }
        return null;
    }

    private static String abbreviate(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= 40 ? trimmed : trimmed.substring(0, 40) + "...";
    }

    /**
     * 关键词/正则规则降级路径（LLM 不可用或输出非法时使用）。
     * <p>
     * 权重约定：投诉略高（1.2）以免被售后词淹没；闲聊略低（0.9）避免寒暄冲掉业务意图。
     * 无任何业务命中时默认闲聊——宁可跳过检索，也不要把无关闲聊灌进知识库。
     */
    IntentResult classifyByRules(String question) {
        String text = question.trim();
        String lowered = text.toLowerCase(Locale.ROOT);

        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, List<String>> hits = new LinkedHashMap<>();
        scores.put(COMPLAINT, 0.0);
        scores.put(AFTER_SALES, 0.0);
        scores.put(PRODUCT, 0.0);
        scores.put(CHITCHAT, 0.0);
        hits.put(COMPLAINT, new ArrayList<>());
        hits.put(AFTER_SALES, new ArrayList<>());
        hits.put(PRODUCT, new ArrayList<>());
        hits.put(CHITCHAT, new ArrayList<>());

        addHits(scores, hits, COMPLAINT, text, lowered, List.of(
                "投诉", "举报", "维权", "差评", "态度差", "骗人", "欺诈", "工商", "消协",
                "不满", "气死", "太差", "垃圾客服", "投诉你们", "要赔偿", "complaint", "fraud", "scam"
        ), 1.2);

        addHits(scores, hits, AFTER_SALES, text, lowered, List.of(
                "退货", "退款", "换货", "售后", "保修", "维修", "破损", "损坏", "少件",
                "取消订单", "未发货", "拒收", "七天无理由", "无理由", "运费险",
                "refund", "return", "exchange", "warranty", "broken", "damaged"
        ), 1.0);

        addHits(scores, hits, PRODUCT, text, lowered, List.of(
                "发货", "多久到", "物流", "快递", "运费", "库存", "有货", "规格", "尺寸",
                "颜色", "价格", "多少钱", "活动", "优惠", "怎么用", "如何使用", "参数",
                "商品", "产品", "型号", "耳机", "订单能不能", "能买吗", "还有货吗",
                "shipping", "delivery", "stock", "price", "spec", "how to use"
        ), 1.0);

        addHits(scores, hits, CHITCHAT, text, lowered, List.of(
                "你好", "您好", "在吗", "嗨", "哈喽", "早上好", "晚上好", "谢谢", "再见",
                "你是谁", "你叫什么", "你是机器人", "你是人工智能", "你能做什么",
                "天气", "气温", "下雨", "无聊", "聊天", "闲聊",
                "hello", "hi", "hey", "thanks", "bye", "who are you"
        ), 0.9);

        if (OFF_TOPIC_CHITCHAT.matcher(text).find()) {
            scores.merge(CHITCHAT, 2.0, Double::sum);
            hits.get(CHITCHAT).add("off-topic-pattern");
        }

        if (text.length() <= 8 && (
                text.contains("你好") || text.contains("在吗") || text.contains("谢谢")
                        || lowered.equals("hi") || lowered.equals("hello") || lowered.equals("hey")
        )) {
            scores.merge(CHITCHAT, 1.5, Double::sum);
            hits.get(CHITCHAT).add("short-greeting");
        }

        double maxBusiness = Math.max(
                scores.get(COMPLAINT),
                Math.max(scores.get(AFTER_SALES), scores.get(PRODUCT))
        );
        double chitchatScore = scores.get(CHITCHAT);

        if (maxBusiness <= 0 && chitchatScore <= 0) {
            return new IntentResult(CHITCHAT, 0.2, "default-chitchat");
        }
        if (maxBusiness <= 0) {
            return new IntentResult(
                    CHITCHAT,
                    Math.min(1.0, chitchatScore / 3.0),
                    summarizeHits(hits.get(CHITCHAT))
            );
        }

        String best = scores.entrySet().stream()
                .max(Comparator
                        .<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue)
                        .thenComparing(entry -> priorityRank(entry.getKey())))
                .map(Map.Entry::getKey)
                .orElse(CHITCHAT);

        double bestScore = scores.getOrDefault(best, 0.0);
        if (bestScore <= 0) {
            return new IntentResult(CHITCHAT, 0.2, "default-chitchat");
        }

        List<String> matched = hits.getOrDefault(best, List.of());
        // /3.0：约命中 3 个加权关键词即接近满分，避免无限累加
        double confidence = Math.min(1.0, bestScore / 3.0);
        return new IntentResult(best, confidence, summarizeHits(matched));
    }

    private static String summarizeHits(List<String> matched) {
        if (matched == null || matched.isEmpty()) {
            return "scored";
        }
        return String.join(",", matched.stream().limit(5).toList());
    }

    private void addHits(
            Map<String, Double> scores,
            Map<String, List<String>> hits,
            String label,
            String text,
            String lowered,
            List<String> keywords,
            double weight
    ) {
        for (String keyword : keywords) {
            String key = keyword.toLowerCase(Locale.ROOT);
            if (text.contains(keyword) || lowered.contains(key)) {
                scores.merge(label, weight, Double::sum);
                hits.get(label).add(keyword);
            }
        }
    }

    /**
     * 同分时的业务优先级：投诉 > 售后 > 产品 > 闲聊。
     * 客服场景宁可将边界问题当投诉/售后处理，避免漏识别高风险情绪。
     */
    private int priorityRank(String label) {
        return switch (label) {
            case COMPLAINT -> 4;
            case AFTER_SALES -> 3;
            case PRODUCT -> 2;
            case CHITCHAT -> 1;
            default -> 0;
        };
    }
}
