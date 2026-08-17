package com.company.aics.agent;

import com.company.aics.rag.OpenAiCompatibleChatClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 独立反思 Agent（层级监督）：评估 Impact / Dag / Review 产出，
 * 决定通过或指定目标 Agent 带着纠正提示重试。
 */
@Component
public class AgentReflectionAgent {

    private static final Logger log = LoggerFactory.getLogger(AgentReflectionAgent.class);

    public static final String TARGET_IMPACT = "impact";
    public static final String TARGET_DAG = "dag";
    public static final String TARGET_REVIEW = "review";

    private final OpenAiCompatibleChatClient chatClient;
    private final ObjectMapper objectMapper;

    public AgentReflectionAgent(OpenAiCompatibleChatClient chatClient, ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 评估某阶段产出。LLM 失败时保守通过（避免反思故障拖垮整条流水线）。
     */
    public ReflectionVerdict evaluate(
            String stage,
            String requirementTitle,
            String requirementContent,
            String candidateJson,
            String programmaticHint
    ) {
        try {
            String user = """
                    【阶段】%s
                    【需求标题】%s
                    【需求正文】%s
                    【程序侧提示（可空）】%s
                    【待评估产出 JSON】
                    %s

                    请只输出 JSON。
                    """.formatted(
                    stage,
                    nullToEmpty(requirementTitle),
                    nullToEmpty(requirementContent),
                    nullToEmpty(programmaticHint),
                    nullToEmpty(candidateJson)
            );
            String raw = chatClient.completeJson(SYSTEM_PROMPT, user, 700);
            JsonNode root = objectMapper.readTree(extractJsonObject(raw));
            boolean approved = root.path("approved").asBoolean(false);
            String retryTarget = normalizeTarget(root.path("retryTarget").asText(null));
            String errorType = blankOr(root.path("errorType").asText(null), "quality");
            String correctionHint = blankOr(root.path("correctionHint").asText(null), root.path("lesson").asText(null));
            List<String> issues = readStringList(root.path("issues"));
            String lesson = blankOr(root.path("lesson").asText(null), correctionHint);
            if (!approved && !StringUtils.hasText(retryTarget)) {
                retryTarget = defaultTargetForStage(stage);
            }
            return new ReflectionVerdict(approved, retryTarget, errorType, issues, correctionHint, lesson);
        } catch (Exception ex) {
            log.warn("ReflectionAgent failed at stage={}, pass-through: {}", stage, ex.getMessage());
            return ReflectionVerdict.passThrough("反思 Agent 不可用，跳过本轮否决。");
        }
    }

    private static String defaultTargetForStage(String stage) {
        if (TARGET_IMPACT.equalsIgnoreCase(stage)) {
            return TARGET_IMPACT;
        }
        if (TARGET_DAG.equalsIgnoreCase(stage)) {
            return TARGET_DAG;
        }
        if (TARGET_REVIEW.equalsIgnoreCase(stage) || "final".equalsIgnoreCase(stage)) {
            return TARGET_REVIEW;
        }
        return TARGET_DAG;
    }

    private static String normalizeTarget(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        if (TARGET_IMPACT.equals(t) || TARGET_DAG.equals(t) || TARGET_REVIEW.equals(t)) {
            return t;
        }
        return null;
    }

    private List<String> readStringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode n : node) {
                String text = n.asText("").trim();
                if (StringUtils.hasText(text)) {
                    values.add(text);
                }
            }
        }
        return values;
    }

    private static String extractJsonObject(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("empty LLM response");
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNl = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                text = text.substring(firstNl + 1, lastFence).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("LLM response is not a JSON object");
        }
        return text.substring(start, end + 1);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String blankOr(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : (StringUtils.hasText(fallback) ? fallback.trim() : null);
    }

    private static final String SYSTEM_PROMPT = """
            你是「反思监督 Agent」(ReflectionAgent)，位于多 Agent 流水线之上的层级评审角色。
            你的职责：评估下级 Agent（Impact / Dag / Review）的产出质量，决定通过或要求指定 Agent 重试。
            评判重点：
            1) 影响面是否覆盖需求涉及的服务，且未胡乱扩展到无关服务
            2) 若程序侧提示存在人工硬约束范围（hardScope），不得因「缺范围外服务」而要求扩面；缺服务应认可 missingEvidence
            3) 任务依赖是否合理（并行/串行），有无环、有无漏依赖
            4) 发布顺序与验证步骤是否可执行
            5) 是否与程序侧提示冲突
            不要改写业务方案本身；只做评估与纠正指令。
            只输出 JSON：
            {
              "approved": true|false,
              "retryTarget": "impact"|"dag"|"review"|null,
              "errorType": "empty_impact|missing_service|bad_dependency|cycle|incomplete|quality|other",
              "issues": ["问题1"],
              "correctionHint": "给目标 Agent 的重试纠正提示",
              "lesson": "写入错误记忆库、供以后自我修正的一句话教训"
            }
            approved=true 时 retryTarget 必须为 null。
            """;

    public record ReflectionVerdict(
            boolean approved,
            String retryTarget,
            String errorType,
            List<String> issues,
            String correctionHint,
            String lesson
    ) {
        static ReflectionVerdict passThrough(String note) {
            return new ReflectionVerdict(true, null, "skipped", List.of(note), null, null);
        }
    }
}
