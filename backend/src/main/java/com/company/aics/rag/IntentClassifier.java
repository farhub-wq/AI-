package com.company.aics.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 客服意图分类器：在调用 LLM 前对用户问题打标。
 * 标签对齐 README：产品咨询 / 售后问题 / 闲聊 / 投诉。
 * 采用多信号加权打分（中英关键词 + 正则短语），高优先级意图可抢占低优先级平局。
 */
@Component
public class IntentClassifier {

    /** README 要求的四类意图。 */
    public static final String PRODUCT = "产品咨询";
    public static final String AFTER_SALES = "售后问题";
    public static final String CHITCHAT = "闲聊";
    public static final String COMPLAINT = "投诉";

    /**
     * 分类结果：标签、置信分、触发命中摘要（便于观测与调试）。
     */
    public record IntentResult(String label, double score, String matchedSignals) {
    }

    /**
     * 对用户问题做意图分类；空输入默认闲聊。
     */
    public IntentResult classify(String question) {
        if (!StringUtils.hasText(question)) {
            return new IntentResult(CHITCHAT, 0.0, "empty");
        }

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

        // 投诉：情绪/维权信号，优先级最高
        addHits(scores, hits, COMPLAINT, text, lowered, List.of(
                "投诉", "举报", "维权", "差评", "态度差", "骗人", "欺诈", "工商", "消协",
                "不满", "气死", "太差", "垃圾", "投诉你们", "complaint", "fraud", "scam"
        ), 1.2);

        // 售后：退换货/退款/损坏/未发货取消等
        addHits(scores, hits, AFTER_SALES, text, lowered, List.of(
                "退货", "退款", "换货", "售后", "保修", "维修", "破损", "损坏", "少件",
                "取消订单", "未发货", "拒收", "七天无理由", "无理由", "运费险",
                "refund", "return", "exchange", "warranty", "broken", "damaged"
        ), 1.0);

        // 产品咨询：发货物流、规格、库存、价格等
        addHits(scores, hits, PRODUCT, text, lowered, List.of(
                "发货", "多久到", "物流", "快递", "运费", "库存", "有货", "规格", "尺寸",
                "颜色", "价格", "多少钱", "活动", "优惠", "怎么用", "如何使用", "参数",
                "shipping", "delivery", "stock", "price", "spec", "how to"
        ), 1.0);

        // 闲聊：寒暄与身份询问
        addHits(scores, hits, CHITCHAT, text, lowered, List.of(
                "你好", "您好", "在吗", "嗨", "哈喽", "早上好", "晚上好", "谢谢", "再见",
                "你是谁", "你叫什么", "你是机器人", "hello", "hi", "hey", "thanks", "bye"
        ), 0.9);

        // 短句寒暄加成
        if (text.length() <= 6 && (text.contains("你好") || text.contains("在吗") || lowered.equals("hi") || lowered.equals("hello"))) {
            scores.merge(CHITCHAT, 1.5, Double::sum);
            hits.get(CHITCHAT).add("short-greeting");
        }

        String best = scores.entrySet().stream()
                .max(Comparator
                        .<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue)
                        .thenComparing(entry -> priorityRank(entry.getKey())))
                .map(Map.Entry::getKey)
                .orElse(PRODUCT);

        double bestScore = scores.getOrDefault(best, 0.0);
        if (bestScore <= 0) {
            return new IntentResult(PRODUCT, 0.1, "default");
        }

        List<String> matched = hits.getOrDefault(best, List.of());
        String signalSummary = matched.isEmpty() ? "scored" : String.join(",", matched.stream().limit(5).toList());
        // 归一到约 [0,1] 的展示分
        double confidence = Math.min(1.0, bestScore / 3.0);
        return new IntentResult(best, confidence, signalSummary);
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

    /** 平局时：投诉 > 售后 > 产品 > 闲聊。 */
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
