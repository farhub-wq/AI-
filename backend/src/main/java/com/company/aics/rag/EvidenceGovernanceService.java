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

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?");

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

        double highScoreCut = deduped.stream()
                .mapToDouble(KnowledgeBaseService.SearchHit::score)
                .max()
                .orElse(0) * 0.75;

        for (KnowledgeBaseService.SearchHit hit : deduped) {
            if (isPolicy(hit)) {
                policies.add(hit);
            } else if (hit.score() >= highScoreCut || highs.size() < 3) {
                highs.add(hit);
            } else {
                backgrounds.add(hit);
            }
        }

        // 预算：政策优先占约 45%，高相关 40%，背景 15%
        int policyBudget = Math.max(800, (int) (maxContextChars * 0.45));
        int highBudget = Math.max(800, (int) (maxContextChars * 0.40));
        int bgBudget = Math.max(400, maxContextChars - policyBudget - highBudget);

        List<LayeredEvidence> policyLayer = takeWithBudget(policies, "must_keep_policy", policyBudget, 4, false, question);
        List<LayeredEvidence> highLayer = takeWithBudget(highs, "high_relevance", highBudget, 4, false, question);
        List<LayeredEvidence> bgLayer = takeWithBudget(backgrounds, "background", bgBudget, 3, true, question);

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
     * 分步校验：回答中的关键数字应能在证据原文中找到，降低编造时效/金额的风险。
     */
    public ConsistencyCheck validateAnswer(String answer, EvidenceBundle bundle) {
        if (!StringUtils.hasText(answer) || bundle == null || bundle.isEmpty()) {
            return new ConsistencyCheck(true, "skip");
        }
        String evidenceBlob = bundle.allLayers().stream()
                .map(LayeredEvidence::displayText)
                .reduce("", (a, b) -> a + "\n" + b);

        Matcher matcher = NUMBER_PATTERN.matcher(answer);
        List<String> unsupported = new ArrayList<>();
        while (matcher.find()) {
            String num = matcher.group();
            // 忽略过短编号噪声（如 E1 中的 1 已由上下文覆盖）；关注 2 位及以上或业务常见数字
            if (num.length() == 1) {
                continue;
            }
            if (!evidenceBlob.contains(num)) {
                unsupported.add(num);
            }
        }
        if (!unsupported.isEmpty()) {
            return new ConsistencyCheck(false, "answer-contains-numbers-not-in-evidence:" + String.join(",", unsupported));
        }
        return new ConsistencyCheck(true, "ok");
    }

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
     * 去重（同 vectorId / 高相似正文）并合并同文档相邻切块。
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
