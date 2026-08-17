package com.company.aics.rag;

import com.company.aics.application.KnowledgeBaseService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 大规模检索下的证据治理：分层、压缩、预算分配，以及回答一致性校验。
 * 对应 README 加分项 5——减轻注意力稀释与规则混淆/幻觉。
 */
@Component
public class EvidenceGovernanceService {

    /**
     * 仅校验「带业务单位的政策/时效/金额类数字」，避免误伤订单号、年份、序号等噪声。
     * 例：7天、48小时、99元、15%、3个工作日。
     */
    private static final Pattern CLAIM_NUMBER_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(?:个)?\\s*(天|日|自然日|工作日|小时|分钟|元|块|万元|%|％|件|次)"
    );

    /**
     * 分层后的单条证据（入模文本可能是原文或抽取摘要）。
     */
    public record LayeredEvidence(
            String evidenceId,
            String layer,
            KnowledgeBaseService.SearchHit hit,
            String displayText
    ) {
    }

    /**
     * 打包结果：供 Prompt 分层展示，并给出引用列表与观测摘要。
     */
    public record EvidenceBundle(
            List<LayeredEvidence> mustKeepPolicy,
            List<LayeredEvidence> highRelevance,
            List<LayeredEvidence> background,
            List<KnowledgeBaseService.SearchHit> citationHits,
            String packingNote
    ) {
        /** @return 是否有任何可入模证据 */
        public boolean isEmpty() {
            return mustKeepPolicy.isEmpty() && highRelevance.isEmpty() && background.isEmpty();
        }

        /** @return 扁平化全部层级，保持编号顺序 */
        public List<LayeredEvidence> allLayers() {
            List<LayeredEvidence> all = new ArrayList<>();
            all.addAll(mustKeepPolicy);
            all.addAll(highRelevance);
            all.addAll(background);
            return all;
        }
    }

    /**
     * 回答一致性校验结果。
     */
    public record ConsistencyCheck(boolean passed, String reason) {
    }

    /**
     * 对阈值过滤后的命中做三段式打包：
     * must_keep_policy → high_relevance → background（背景做抽取式压缩）。
     * <p>
     * 设计动机：大量命中直接塞进 Prompt 会稀释注意力、混写规则；
     * 分层 + 字符预算强制「政策优先、背景压缩」，并保证高相关层至少有少量条目可读。
     */
    public EvidenceBundle pack(
            List<KnowledgeBaseService.SearchHit> filteredHits,
            String question,
            int maxContextChars
    ) {
        if (filteredHits == null || filteredHits.isEmpty()) {
            return new EvidenceBundle(List.of(), List.of(), List.of(), List.of(), "no-hits");
        }

        List<KnowledgeBaseService.SearchHit> deduped = dedupeAndMerge(filteredHits);
        List<KnowledgeBaseService.SearchHit> policies = new ArrayList<>();
        List<KnowledgeBaseService.SearchHit> highs = new ArrayList<>();
        List<KnowledgeBaseService.SearchHit> backgrounds = new ArrayList<>();

        // 相对最高分的 75% 切高相关；避免绝对阈值在不同 Embedding 量纲下失效
        double highScoreCut = deduped.stream()
                .mapToDouble(KnowledgeBaseService.SearchHit::score)
                .max()
                .orElse(0) * 0.75;

        for (KnowledgeBaseService.SearchHit hit : deduped) {
            if (isPolicy(hit)) {
                policies.add(hit);
            } else if (hit.score() >= highScoreCut || highs.size() < 3) {
                // 未满 3 条时强制抬入高相关，避免全被压到背景层导致本题无直接支撑
                highs.add(hit);
            } else {
                backgrounds.add(hit);
            }
        }

        // 预算：政策优先占约 45%，高相关 40%，背景 15%（与 Prompt「政策层必须遵守」对齐）
        int policyBudget = Math.max(800, (int) (maxContextChars * 0.45));
        int highBudget = Math.max(800, (int) (maxContextChars * 0.40));
        int bgBudget = Math.max(400, maxContextChars - policyBudget - highBudget);

        List<LayeredEvidence> policyLayer = takeWithBudget(policies, "must_keep_policy", policyBudget, 4, false, question);
        List<LayeredEvidence> highLayer = takeWithBudget(highs, "high_relevance", highBudget, 4, false, question);
        List<LayeredEvidence> bgLayer = takeWithBudget(backgrounds, "background", bgBudget, 3, true, question);

        // 跨层统一重排为 E1…En，保证 Prompt 编号与前端 citation 一一对应
        renumber(policyLayer, highLayer, bgLayer);

        List<KnowledgeBaseService.SearchHit> citations = new ArrayList<>();
        for (LayeredEvidence item : concat(policyLayer, highLayer, bgLayer)) {
            citations.add(item.hit());
        }

        String note = "policy=" + policyLayer.size()
                + ", high=" + highLayer.size()
                + ", background=" + bgLayer.size()
                + ", inputHits=" + filteredHits.size()
                + ", afterDedupe=" + deduped.size();
        return new EvidenceBundle(policyLayer, highLayer, bgLayer, citations, note);
    }

    /**
     * 分步校验：仅检查回答中带业务单位的数字（天/元/%/次等）是否出现在证据中，
     * 降低编造时效/金额风险，同时避免订单号、年份等误伤。
     */
    public ConsistencyCheck validateAnswer(String answer, EvidenceBundle bundle) {
        if (!StringUtils.hasText(answer) || bundle == null || bundle.isEmpty()) {
            return new ConsistencyCheck(true, "skip");
        }
        String evidenceBlob = bundle.allLayers().stream()
                .map(LayeredEvidence::displayText)
                .reduce("", (a, b) -> a + "\n" + b);

        Matcher matcher = CLAIM_NUMBER_PATTERN.matcher(answer);
        List<String> unsupported = new ArrayList<>();
        while (matcher.find()) {
            String num = matcher.group(1);
            String unit = matcher.group(2);
            String claim = num + unit;
            // 数字本身或「数字+单位」任一能在证据中找到即通过
            if (!evidenceBlob.contains(num) && !evidenceBlob.contains(claim)) {
                unsupported.add(claim);
            }
        }
        if (!unsupported.isEmpty()) {
            return new ConsistencyCheck(
                    false,
                    "answer-contains-claim-numbers-not-in-evidence:" + String.join(",", unsupported)
            );
        }
        return new ConsistencyCheck(true, "ok");
    }

    /**
     * 按层填充证据，受条数与字符预算约束。
     * 首条允许超出预算：宁可略超也要保证该层至少有一条可读证据，避免空层。
     */
    private List<LayeredEvidence> takeWithBudget(
            List<KnowledgeBaseService.SearchHit> source,
            String layer,
            int budget,
            int maxItems,
            boolean summarize,
            String question
    ) {
        List<LayeredEvidence> result = new ArrayList<>();
        int used = 0;
        int index = 1;
        for (KnowledgeBaseService.SearchHit hit : source) {
            if (result.size() >= maxItems) {
                break;
            }
            String text = summarize ? extractiveSummary(hit.chunk().content(), question) : hit.chunk().content().trim();
            if (!StringUtils.hasText(text)) {
                continue;
            }
            if (!result.isEmpty() && used + text.length() > budget) {
                continue;
            }
            result.add(new LayeredEvidence("E" + index++, layer, hit, text));
            used += text.length();
        }
        return result;
    }

    /** 跨层重新编号为连续 E1…En，避免各层本地编号冲突。 */
    private void renumber(List<LayeredEvidence> a, List<LayeredEvidence> b, List<LayeredEvidence> c) {
        List<LayeredEvidence> all = concat(a, b, c);
        List<LayeredEvidence> rebuilt = new ArrayList<>();
        int i = 1;
        for (LayeredEvidence item : all) {
            rebuilt.add(new LayeredEvidence("E" + i++, item.layer(), item.hit(), item.displayText()));
        }
        a.clear();
        b.clear();
        c.clear();
        for (LayeredEvidence item : rebuilt) {
            switch (item.layer()) {
                case "must_keep_policy" -> a.add(item);
                case "high_relevance" -> b.add(item);
                default -> c.add(item);
            }
        }
    }

    @SafeVarargs
    private final List<LayeredEvidence> concat(List<LayeredEvidence>... parts) {
        List<LayeredEvidence> all = new ArrayList<>();
        for (List<LayeredEvidence> part : parts) {
            all.addAll(part);
        }
        return all;
    }

    /**
     * 去重（同 vectorId / 正文指纹）并合并同文档相邻切块。
     * 合并动机：切块边界常把同一条政策拆成两段，分开展示易导致模型混写或漏条件；
     * 合并上限约 900 字，避免单条证据过长挤占预算。
     */
    private List<KnowledgeBaseService.SearchHit> dedupeAndMerge(List<KnowledgeBaseService.SearchHit> hits) {
        List<KnowledgeBaseService.SearchHit> sorted = hits.stream()
                .sorted(Comparator.comparing(KnowledgeBaseService.SearchHit::score).reversed())
                .toList();

        List<KnowledgeBaseService.SearchHit> unique = new ArrayList<>();
        Set<String> seenVector = new LinkedHashSet<>();
        Set<String> seenFingerprints = new LinkedHashSet<>();
        for (KnowledgeBaseService.SearchHit hit : sorted) {
            String vid = hit.chunk().vectorId();
            if (vid != null && !seenVector.add(vid)) {
                continue;
            }
            String fp = fingerprint(hit.chunk().content());
            if (!seenFingerprints.add(fp)) {
                continue;
            }
            unique.add(hit);
        }

        // 同文档按 chunkIndex 合并相邻段，减少碎片导致的规则割裂
        unique.sort(Comparator
                .comparing((KnowledgeBaseService.SearchHit h) -> h.document().id())
                .thenComparing(h -> h.chunk().chunkIndex()));

        List<KnowledgeBaseService.SearchHit> merged = new ArrayList<>();
        KnowledgeBaseService.SearchHit pending = null;
        for (KnowledgeBaseService.SearchHit hit : unique) {
            if (pending == null) {
                pending = hit;
                continue;
            }
            boolean sameDoc = Objects.equals(pending.document().id(), hit.document().id());
            boolean adjacent = hit.chunk().chunkIndex() == pending.chunk().chunkIndex() + 1;
            boolean shortEnough = pending.chunk().content().length() + hit.chunk().content().length() < 900;
            if (sameDoc && adjacent && shortEnough) {
                pending = new KnowledgeBaseService.SearchHit(
                        pending.document(),
                        new com.company.aics.domain.DomainModels.DocumentChunk(
                                pending.chunk().id(),
                                pending.chunk().documentId(),
                                pending.chunk().kbId(),
                                pending.chunk().vectorId(),
                                pending.chunk().chunkIndex(),
                                pending.chunk().sectionTitle(),
                                pending.chunk().priority(),
                                pending.chunk().content().trim() + "\n" + hit.chunk().content().trim(),
                                pending.chunk().metadata()
                        ),
                        Math.max(pending.score(), hit.score())
                );
            } else {
                merged.add(pending);
                pending = hit;
            }
        }
        if (pending != null) {
            merged.add(pending);
        }

        merged.sort(Comparator.comparing(KnowledgeBaseService.SearchHit::score).reversed());
        return merged;
    }

    private boolean isPolicy(KnowledgeBaseService.SearchHit hit) {
        String priority = hit.document().priority();
        String docType = hit.document().docType();
        return "policy".equalsIgnoreCase(priority) || "policy".equalsIgnoreCase(docType);
    }

    /** 抽取式摘要：保留含问题关键词的句子，否则取前 120 字。 */
    private String extractiveSummary(String content, String question) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        String[] sentences = normalized.split("(?<=[。！？；\\n])");
        List<String> keywords = extractQuestionTokens(question);
        StringBuilder picked = new StringBuilder();
        for (String sentence : sentences) {
            String s = sentence.trim();
            if (s.isEmpty()) {
                continue;
            }
            boolean hit = keywords.stream().anyMatch(k -> s.contains(k) || s.toLowerCase(Locale.ROOT).contains(k));
            if (hit) {
                if (picked.length() > 0) {
                    picked.append(' ');
                }
                picked.append(s);
                if (picked.length() >= 160) {
                    break;
                }
            }
        }
        if (picked.length() > 0) {
            return picked.length() > 200 ? picked.substring(0, 200) + "…" : picked.toString();
        }
        return normalized.length() > 120 ? normalized.substring(0, 120) + "…" : normalized;
    }

    private List<String> extractQuestionTokens(String question) {
        if (!StringUtils.hasText(question)) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String part : question.split("[\\s,，。！？、/]+")) {
            if (part.length() >= 2) {
                tokens.add(part.toLowerCase(Locale.ROOT));
            }
        }
        return tokens;
    }

    private String fingerprint(String content) {
        String compact = content == null ? "" : content.replaceAll("\\s+", "");
        if (compact.length() > 80) {
            compact = compact.substring(0, 80);
        }
        return compact;
    }
}
