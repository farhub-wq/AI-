package com.company.aics.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 客服意图分类器：在调用 LLM / RAG 前对用户问题打标。
 * 标签对齐 README：产品咨询 / 售后问题 / 闲聊 / 投诉。
 * <p>
 * 规则要点：
 * <ul>
 *   <li>多信号加权（关键词 + 正则）</li>
 *   <li>无业务信号命中时默认「闲聊」，禁止默认成「产品咨询」</li>
 *   <li>平局时：投诉 &gt; 售后 &gt; 产品 &gt; 闲聊</li>
 * </ul>
 */
@Component
public class IntentClassifier {

    /** README 要求的四类意图。 */
    public static final String PRODUCT = "产品咨询";
    public static final String AFTER_SALES = "售后问题";
    public static final String CHITCHAT = "闲聊";
    public static final String COMPLAINT = "投诉";

    /** 明显与购物无关的闲聊/百科问句（天气、时间、笑话等）。 */
    private static final Pattern OFF_TOPIC_CHITCHAT = Pattern.compile(
            "(天气|气温|下雨|下雪|几度|预报|"
                    + "几点|什么时候了|现在时间|"
                    + "笑话|唱歌|讲故事|"
                    + "你吃饭|吃了吗|在干嘛|干什么呢|"
                    + "地球|宇宙|明星|足球|篮球|"
                    + "weather|joke|time)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 分类结果：标签、置信分、触发命中摘要（便于观测与调试）。
     */
    public record IntentResult(String label, double score, String matchedSignals) {
    }

    /**
     * 对用户问题做意图分类；空输入或无业务信号默认闲聊。
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
                "不满", "气死", "太差", "垃圾客服", "投诉你们", "要赔偿", "complaint", "fraud", "scam"
        ), 1.2);

        // 售后：退换货/退款/损坏/未发货取消等
        addHits(scores, hits, AFTER_SALES, text, lowered, List.of(
                "退货", "退款", "换货", "售后", "保修", "维修", "破损", "损坏", "少件",
                "取消订单", "未发货", "拒收", "七天无理由", "无理由", "运费险",
                "refund", "return", "exchange", "warranty", "broken", "damaged"
        ), 1.0);

        // 产品咨询：发货物流、规格、库存、价格等（避免用过短易误伤词，如单独「如何」）
        addHits(scores, hits, PRODUCT, text, lowered, List.of(
                "发货", "多久到", "物流", "快递", "运费", "库存", "有货", "规格", "尺寸",
                "颜色", "价格", "多少钱", "活动", "优惠", "怎么用", "如何使用", "参数",
                "商品", "产品", "型号", "耳机", "订单能不能", "能买吗", "还有货吗",
                "shipping", "delivery", "stock", "price", "spec", "how to use"
        ), 1.0);

        // 闲聊：寒暄、身份询问、与购物无关的日常问句
        addHits(scores, hits, CHITCHAT, text, lowered, List.of(
                "你好", "您好", "在吗", "嗨", "哈喽", "早上好", "晚上好", "谢谢", "再见",
                "你是谁", "你叫什么", "你是机器人", "你是人工智能", "你能做什么",
                "天气", "气温", "下雨", "无聊", "聊天", "闲聊",
                "hello", "hi", "hey", "thanks", "bye", "who are you"
        ), 0.9);

        // 正则：明显离题闲聊（如「今天天气如何」）
        if (OFF_TOPIC_CHITCHAT.matcher(text).find()) {
            scores.merge(CHITCHAT, 2.0, Double::sum);
            hits.get(CHITCHAT).add("off-topic-pattern");
        }

        // 短句寒暄加成
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

        // 无任何业务信号：默认闲聊（修复「今天天气如何」被标成产品咨询）
        if (maxBusiness <= 0 && chitchatScore <= 0) {
            return new IntentResult(CHITCHAT, 0.2, "default-chitchat");
        }
        // 仅有闲聊信号，或闲聊显著高于业务信号
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
