package com.company.aics.agent;

import com.company.aics.domain.DomainModels;
import com.company.aics.rag.OpenAiCompatibleChatClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Agent 规划 LLM 助手：在规则/检索主链路产出 DAG 之后，仅做文案润色与评审说明校验。
 * <p>
 * 硬约束：不得改服务集合、任务 ID、dependsOn、并行组、发布顺序；失败则原样返回主链路结果。
 */
@Component
public class AgentPlanLlmAssistant {

    private static final Logger log = LoggerFactory.getLogger(AgentPlanLlmAssistant.class);

    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_SKIPPED = "skipped";
    public static final String STATUS_FAILED = "failed";

    private final OpenAiCompatibleChatClient chatClient;
    private final ObjectMapper objectMapper;

    public AgentPlanLlmAssistant(OpenAiCompatibleChatClient chatClient, ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 对已锁定结构的规划做 LLM 润色；失败时返回 status=failed 且字段保持输入原样。
     */
    public AssistResult polish(
            String requirementTitle,
            String requirementContent,
            List<DomainModels.ImpactedService> impactedServices,
            List<DomainModels.AgentTask> tasks,
            List<String> validationSteps,
            List<String> reviewChecklist,
            List<String> suggestedReleaseOrder,
            List<List<String>> parallelGroups
    ) {
        if (impactedServices == null || impactedServices.isEmpty()) {
            return new AssistResult(
                    impactedServices == null ? List.of() : impactedServices,
                    tasks == null ? List.of() : tasks,
                    validationSteps == null ? List.of() : validationSteps,
                    reviewChecklist == null ? List.of() : reviewChecklist,
                    null,
                    STATUS_SKIPPED
            );
        }

        try {
            String userPayload = buildUserPayload(
                    requirementTitle,
                    requirementContent,
                    impactedServices,
                    tasks,
                    validationSteps,
                    reviewChecklist,
                    suggestedReleaseOrder,
                    parallelGroups
            );
            String raw = chatClient.completeJson(SYSTEM_PROMPT, userPayload, 1200);
            JsonNode root = objectMapper.readTree(extractJsonObject(raw));
            return applyLocked(root, impactedServices, tasks, validationSteps, reviewChecklist);
        } catch (Exception ex) {
            log.warn("Agent plan LLM polish failed, keep rule-based text: {}", ex.getMessage());
            return new AssistResult(
                    impactedServices,
                    tasks,
                    validationSteps,
                    reviewChecklist,
                    null,
                    STATUS_FAILED
            );
        }
    }

    private AssistResult applyLocked(
            JsonNode root,
            List<DomainModels.ImpactedService> impactedServices,
            List<DomainModels.AgentTask> tasks,
            List<String> validationSteps,
            List<String> reviewChecklist
    ) {
        Set<String> lockedServices = impactedServices.stream()
                .map(DomainModels.ImpactedService::serviceCode)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        Map<Long, DomainModels.AgentTask> lockedTasks = new LinkedHashMap<>();
        for (DomainModels.AgentTask task : tasks) {
            lockedTasks.put(task.taskId(), task);
        }

        Map<String, String> reasonByService = new LinkedHashMap<>();
        JsonNode impactedNode = root.path("impactedServiceReasons");
        if (impactedNode.isArray()) {
            for (JsonNode item : impactedNode) {
                String code = item.path("serviceCode").asText("");
                String reason = item.path("reason").asText("");
                if (lockedServices.contains(code) && StringUtils.hasText(reason)) {
                    reasonByService.put(code, reason.trim());
                }
            }
        }

        Map<Long, String> reasonByTask = new LinkedHashMap<>();
        JsonNode taskNode = root.path("taskReasons");
        if (taskNode.isArray()) {
            for (JsonNode item : taskNode) {
                long taskId = item.path("taskId").asLong(Long.MIN_VALUE);
                String reason = item.path("reason").asText("");
                if (lockedTasks.containsKey(taskId) && StringUtils.hasText(reason)) {
                    reasonByTask.put(taskId, reason.trim());
                }
            }
        }

        List<DomainModels.ImpactedService> polishedImpacted = impactedServices.stream()
                .map(item -> new DomainModels.ImpactedService(
                        item.serviceCode(),
                        item.serviceName(),
                        reasonByService.getOrDefault(item.serviceCode(), item.reason())
                ))
                .toList();

        List<DomainModels.AgentTask> polishedTasks = tasks.stream()
                .map(task -> new DomainModels.AgentTask(
                        task.taskId(),
                        task.taskName(),
                        task.targetService(),
                        task.executionMode(),
                        task.dependsOn(),
                        reasonByTask.getOrDefault(task.taskId(), task.reason()),
                        task.ownerTeam(),
                        task.dependencyType()
                ))
                .toList();

        List<String> polishedValidation = readStringList(root.path("validationSteps"));
        if (polishedValidation.isEmpty()) {
            polishedValidation = validationSteps;
        }
        List<String> polishedChecklist = readStringList(root.path("reviewChecklist"));
        if (polishedChecklist.isEmpty()) {
            polishedChecklist = reviewChecklist;
        }

        String summary = root.path("assistSummary").asText(null);
        if (!StringUtils.hasText(summary)) {
            summary = null;
        } else {
            summary = summary.trim();
        }

        // 若 LLM 几乎没改任何东西，仍算 success（调用成功）
        return new AssistResult(
                polishedImpacted,
                polishedTasks,
                polishedValidation,
                polishedChecklist,
                summary,
                STATUS_SUCCESS
        );
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String text = item.asText("");
            if (StringUtils.hasText(text)) {
                values.add(text.trim());
            }
        }
        return values;
    }

    private String buildUserPayload(
            String requirementTitle,
            String requirementContent,
            List<DomainModels.ImpactedService> impactedServices,
            List<DomainModels.AgentTask> tasks,
            List<String> validationSteps,
            List<String> reviewChecklist,
            List<String> suggestedReleaseOrder,
            List<List<String>> parallelGroups
    ) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("requirementTitle", nullToEmpty(requirementTitle));
        root.put("requirementContent", nullToEmpty(requirementContent));

        ArrayNode impacted = root.putArray("impactedServices");
        for (DomainModels.ImpactedService item : impactedServices) {
            ObjectNode node = impacted.addObject();
            node.put("serviceCode", item.serviceCode());
            node.put("serviceName", item.serviceName());
            node.put("reason", item.reason());
        }

        ArrayNode taskArr = root.putArray("tasks");
        for (DomainModels.AgentTask task : tasks) {
            ObjectNode node = taskArr.addObject();
            node.put("taskId", task.taskId());
            node.put("taskName", task.taskName());
            node.put("targetService", task.targetService());
            node.put("executionMode", task.executionMode());
            ArrayNode deps = node.putArray("dependsOn");
            if (task.dependsOn() != null) {
                for (Long dep : task.dependsOn()) {
                    deps.add(dep);
                }
            }
            node.put("reason", task.reason());
        }

        ArrayNode validation = root.putArray("validationSteps");
        for (String step : validationSteps) {
            validation.add(step);
        }
        ArrayNode checklist = root.putArray("reviewChecklist");
        for (String item : reviewChecklist) {
            checklist.add(item);
        }
        ArrayNode release = root.putArray("suggestedReleaseOrder");
        if (suggestedReleaseOrder != null) {
            for (String code : suggestedReleaseOrder) {
                release.add(code);
            }
        }
        ArrayNode parallel = root.putArray("parallelGroups");
        if (parallelGroups != null) {
            for (List<String> group : parallelGroups) {
                ArrayNode g = parallel.addArray();
                for (String name : group) {
                    g.add(name);
                }
            }
        }

        return """
                以下 JSON 是主链路已锁定的变更规划（服务集合/任务依赖/并行组/发布顺序不可改）。
                请仅润色说明文案，并输出指定 JSON。

                """ + objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
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

    private static final String SYSTEM_PROMPT = """
            你是资深研发变更评审助手。输入是已由规则引擎锁定的微服务变更规划。
            你只能润色文案，提升可读性与评审友好度，并做轻量一致性检查说明。

            硬性禁止：
            1. 增删改 serviceCode / taskId / targetService / dependsOn / parallelGroups / suggestedReleaseOrder
            2. 编造未出现在输入中的微服务
            3. 输出 Markdown，只输出一个 JSON 对象

            输出 JSON schema：
            {
              "assistSummary": "2-4 句中文，概括影响面与发布注意点",
              "impactedServiceReasons": [{"serviceCode":"...","reason":"..."}],
              "taskReasons": [{"taskId":1,"reason":"..."}],
              "validationSteps": ["..."],
              "reviewChecklist": ["..."]
            }

            要求：reason / validationSteps / reviewChecklist 用中文，具体可执行；
            impactedServiceReasons 与 taskReasons 必须覆盖输入中全部服务与任务。
            """;

    public record AssistResult(
            List<DomainModels.ImpactedService> impactedServices,
            List<DomainModels.AgentTask> tasks,
            List<String> validationSteps,
            List<String> reviewChecklist,
            String assistSummary,
            String status
    ) {
        public AssistResult {
            Objects.requireNonNull(status);
        }
    }
}
