package com.company.aics.agent;

import com.company.aics.application.KnowledgeBaseService;
import com.company.aics.domain.DomainModels;
import com.company.aics.persistence.AppDataStore;
import com.company.aics.rag.OpenAiCompatibleChatClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 研发变更规划多 Agent 流水线（对齐 Read.md 加分项 6）：
 * <pre>
 * Tool 接地 → ImpactAgent → Reflection → DagAgent → Reflection → ReviewAgent → Reflection(final)
 * </pre>
 * 层级：ReflectionAgent 监督下级产出并可指定重试；错误写入 {@code agent_error_memory}，
 * 后续规划注入历史教训实现自我修正。规则引擎仅作失败降级。
 */
@Component
public class AgentMultiAgentPipeline {

    private static final Logger log = LoggerFactory.getLogger(AgentMultiAgentPipeline.class);

    public static final String MODE_MULTI_AGENT_LLM = "multi_agent_llm";
    public static final String MODE_RULES_FALLBACK = "rules_fallback";

    private static final int MAX_REFLECTION_RETRIES_PER_STAGE = 1;

    private final OpenAiCompatibleChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final AgentReflectionAgent reflectionAgent;
    private final AppDataStore appDataStore;

    public AgentMultiAgentPipeline(
            OpenAiCompatibleChatClient chatClient,
            ObjectMapper objectMapper,
            AgentReflectionAgent reflectionAgent,
            AppDataStore appDataStore
    ) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.reflectionAgent = reflectionAgent;
        this.appDataStore = appDataStore;
    }

    /**
     * 尝试 LLM 多 Agent + 反思监督规划；失败返回 empty，由调用方降级规则路径。
     */
    public Optional<PipelineResult> tryPlan(
            String requirementTitle,
            String requirementContent,
            Set<String> scopedServiceCodes,
            List<KnowledgeBaseService.SearchHit> hits,
            Map<String, DomainModels.ServiceCatalogItem> catalogIndex,
            List<DomainModels.ServiceDependency> dependencyGraph
    ) {
        List<String> trace = new ArrayList<>();
        int reflectionRetries = 0;
        try {
            ToolContext tools = buildToolContext(scopedServiceCodes, hits, catalogIndex, dependencyGraph);
            if (tools.catalog().isEmpty()) {
                return Optional.empty();
            }
            trace.add("Tool接地：目录=" + tools.catalog().size()
                    + " 命中=" + tools.evidenceHits().size()
                    + " 依赖边=" + tools.dependencyGraph().size());

            String impactLessons = formatLessons(appDataStore.listRecentAgentLessons(
                    AgentReflectionAgent.TARGET_IMPACT, 5));
            String dagLessons = formatLessons(appDataStore.listRecentAgentLessons(
                    AgentReflectionAgent.TARGET_DAG, 5));
            String reviewLessons = formatLessons(appDataStore.listRecentAgentLessons(
                    AgentReflectionAgent.TARGET_REVIEW, 5));
            if (StringUtils.hasText(impactLessons) || StringUtils.hasText(dagLessons)) {
                trace.add("已注入历史错误教训（自我修正）");
            }

            ImpactResult impact = runImpactAgent(requirementTitle, requirementContent, tools, impactLessons, null);
            impact = enforceScope(impact, tools, trace);
            if (impact.impactedServices().isEmpty()) {
                recordError(AgentReflectionAgent.TARGET_IMPACT, "impact", "empty_impact",
                        "ImpactAgent 返回空影响面", "必须至少选出一个相关微服务", requirementTitle);
                trace.add("ImpactAgent 空结果 → 失败");
                return Optional.empty();
            }
            trace.add("ImpactAgent：影响面=" + impact.impactedServices().size()
                    + (tools.scope().isEmpty() ? "" : "（已按人工范围硬约束）"));

            String scopeHint = tools.scope().isEmpty()
                    ? null
                    : "人工服务范围为硬约束，只能使用：" + String.join(",", tools.scope())
                    + "。不得要求扩到范围外服务；缺服务请写入 missingEvidence，不要否决仅为了扩面。";

            for (int i = 0; i < MAX_REFLECTION_RETRIES_PER_STAGE; i++) {
                AgentReflectionAgent.ReflectionVerdict verdict = reflectionAgent.evaluate(
                        AgentReflectionAgent.TARGET_IMPACT,
                        requirementTitle,
                        requirementContent,
                        objectMapper.writeValueAsString(Map.of(
                                "impactedServices", impact.impactedServices(),
                                "hardScope", tools.scope()
                        )),
                        scopeHint
                );
                if (verdict.approved()) {
                    trace.add("Reflection@impact：通过");
                    break;
                }
                reflectionRetries++;
                persistReflectionError(verdict, AgentReflectionAgent.TARGET_IMPACT, "impact", requirementTitle);
                trace.add("Reflection@impact：否决 → 重试 Impact（" + String.join("；", verdict.issues()) + "）");
                impact = runImpactAgent(
                        requirementTitle, requirementContent, tools, impactLessons, verdict.correctionHint()
                );
                impact = enforceScope(impact, tools, trace);
                if (impact.impactedServices().isEmpty()) {
                    return Optional.empty();
                }
            }

            DagResult dag = runDagAgent(
                    requirementTitle, requirementContent, tools, impact, null, dagLessons
            );
            String dagError = validateDag(dag, impact);
            if (dagError != null) {
                reflectionRetries++;
                recordError(AgentReflectionAgent.TARGET_DAG, "dag", "validation",
                        dagError, "修正 dependsOn/任务集合后重输出，禁止环依赖", requirementTitle);
                trace.add("程序校验 DAG 失败：" + dagError + " → 重试");
                dag = runDagAgent(
                        requirementTitle, requirementContent, tools, impact, dagError, dagLessons
                );
                dagError = validateDag(dag, impact);
                if (dagError != null) {
                    recordError(AgentReflectionAgent.TARGET_DAG, "dag", "validation",
                            dagError, "严格保证 taskId 唯一且无环", requirementTitle);
                    trace.add("DagAgent 重试仍无效：" + dagError);
                    return Optional.empty();
                }
            }
            trace.add("DagAgent：任务=" + dag.tasks().size());

            for (int i = 0; i < MAX_REFLECTION_RETRIES_PER_STAGE; i++) {
                String dagPayload = objectMapper.writeValueAsString(Map.of(
                        "tasks", dag.tasks(),
                        "parallelGroups", dag.parallelGroups(),
                        "suggestedReleaseOrder", dag.suggestedReleaseOrder()
                ));
                AgentReflectionAgent.ReflectionVerdict verdict = reflectionAgent.evaluate(
                        AgentReflectionAgent.TARGET_DAG,
                        requirementTitle,
                        requirementContent,
                        dagPayload,
                        null
                );
                if (verdict.approved()) {
                    trace.add("Reflection@dag：通过");
                    break;
                }
                reflectionRetries++;
                persistReflectionError(verdict, AgentReflectionAgent.TARGET_DAG, "dag", requirementTitle);
                String hint = StringUtils.hasText(verdict.correctionHint())
                        ? verdict.correctionHint()
                        : String.join("；", verdict.issues());
                trace.add("Reflection@dag：否决 → 重试 Dag");
                dag = runDagAgent(
                        requirementTitle, requirementContent, tools, impact, hint, dagLessons
                );
                dagError = validateDag(dag, impact);
                if (dagError != null) {
                    recordError(AgentReflectionAgent.TARGET_DAG, "dag", "validation",
                            dagError, hint, requirementTitle);
                    trace.add("反思重试后程序校验仍失败：" + dagError);
                    return Optional.empty();
                }
            }

            ReviewResult review = runReviewAgent(
                    requirementTitle, requirementContent, impact, dag, tools, reviewLessons, null
            );
            trace.add("ReviewAgent：完成");

            for (int i = 0; i < MAX_REFLECTION_RETRIES_PER_STAGE; i++) {
                String finalPayload = objectMapper.writeValueAsString(Map.of(
                        "impactedServices", impact.impactedServices(),
                        "tasks", dag.tasks(),
                        "parallelGroups", dag.parallelGroups(),
                        "suggestedReleaseOrder", dag.suggestedReleaseOrder(),
                        "validationSteps", review.validationSteps(),
                        "reviewChecklist", review.reviewChecklist(),
                        "assistSummary", review.assistSummary() == null ? "" : review.assistSummary()
                ));
                AgentReflectionAgent.ReflectionVerdict verdict = reflectionAgent.evaluate(
                        "final",
                        requirementTitle,
                        requirementContent,
                        finalPayload,
                        scopeHint
                );
                if (verdict.approved()) {
                    trace.add("Reflection@final：通过");
                    break;
                }
                reflectionRetries++;
                persistReflectionError(verdict, verdict.retryTarget() == null
                        ? AgentReflectionAgent.TARGET_REVIEW
                        : verdict.retryTarget(), "final", requirementTitle);
                String target = verdict.retryTarget() == null
                        ? AgentReflectionAgent.TARGET_REVIEW
                        : verdict.retryTarget();
                trace.add("Reflection@final：否决 → 重试 " + target);
                if (AgentReflectionAgent.TARGET_IMPACT.equals(target)) {
                    impact = runImpactAgent(
                            requirementTitle, requirementContent, tools, impactLessons, verdict.correctionHint()
                    );
                    impact = enforceScope(impact, tools, trace);
                    if (impact.impactedServices().isEmpty()) {
                        return Optional.empty();
                    }
                    dag = runDagAgent(
                            requirementTitle, requirementContent, tools, impact, verdict.correctionHint(), dagLessons
                    );
                    if (validateDag(dag, impact) != null) {
                        return Optional.empty();
                    }
                    review = runReviewAgent(
                            requirementTitle, requirementContent, impact, dag, tools, reviewLessons, null
                    );
                } else if (AgentReflectionAgent.TARGET_DAG.equals(target)) {
                    dag = runDagAgent(
                            requirementTitle, requirementContent, tools, impact, verdict.correctionHint(), dagLessons
                    );
                    if (validateDag(dag, impact) != null) {
                        return Optional.empty();
                    }
                    review = runReviewAgent(
                            requirementTitle, requirementContent, impact, dag, tools, reviewLessons, null
                    );
                } else {
                    review = runReviewAgent(
                            requirementTitle, requirementContent, impact, dag, tools,
                            reviewLessons, verdict.correctionHint()
                    );
                }
            }

            List<String> missing = new ArrayList<>(impact.missingEvidence());
            if (hits == null || hits.isEmpty()) {
                missing.add("技术文档检索为空，规划主要依赖服务目录与依赖表，请人工复核。");
            }

            String status = impact.impactedServices().isEmpty()
                    ? "failed"
                    : (missing.isEmpty() ? "success" : "partial");

            String summary = review.assistSummary();
            if (!StringUtils.hasText(summary) && reflectionRetries > 0) {
                summary = "多 Agent 规划完成（含 " + reflectionRetries + " 次反思重试）。";
            }

            return Optional.of(new PipelineResult(
                    status,
                    impact.impactedServices(),
                    dag.parallelGroups(),
                    dag.tasks(),
                    review.validationSteps().isEmpty() ? dag.validationSteps() : review.validationSteps(),
                    missing,
                    tools.evidenceHits(),
                    tools.edgesUsed(impact.impactedServices()),
                    dag.suggestedReleaseOrder(),
                    review.reviewChecklist().isEmpty() ? dag.reviewChecklist() : review.reviewChecklist(),
                    summary,
                    AgentPlanLlmAssistant.STATUS_SUCCESS,
                    MODE_MULTI_AGENT_LLM,
                    List.copyOf(trace),
                    reflectionRetries
            ));
        } catch (Exception ex) {
            log.warn("Multi-agent planning pipeline failed: {}", ex.getMessage());
            recordError("pipeline", "pipeline", "exception",
                    ex.getMessage() == null ? "unknown" : ex.getMessage(),
                    "检查 LLM 可用性与 JSON 格式后重试",
                    requirementTitle);
            return Optional.empty();
        }
    }

    private void persistReflectionError(
            AgentReflectionAgent.ReflectionVerdict verdict,
            String agentRole,
            String stage,
            String requirementTitle
    ) {
        String detail = verdict.issues() == null || verdict.issues().isEmpty()
                ? "ReflectionAgent 否决"
                : String.join("；", verdict.issues());
        recordError(
                agentRole,
                stage,
                StringUtils.hasText(verdict.errorType()) ? verdict.errorType() : "quality",
                detail,
                StringUtils.hasText(verdict.lesson()) ? verdict.lesson() : verdict.correctionHint(),
                requirementTitle
        );
    }

    private void recordError(
            String agentRole,
            String stage,
            String errorType,
            String errorDetail,
            String correctionHint,
            String requirementTitle
    ) {
        try {
            appDataStore.saveAgentErrorMemory(new DomainModels.AgentErrorMemory(
                    null,
                    agentRole,
                    stage,
                    errorType == null ? "other" : errorType,
                    errorDetail == null ? "" : errorDetail,
                    correctionHint,
                    requirementTitle,
                    null,
                    OffsetDateTime.now(ZoneOffset.UTC)
            ));
        } catch (Exception ex) {
            log.warn("Failed to persist agent error memory: {}", ex.getMessage());
        }
    }

    private static String formatLessons(List<DomainModels.AgentErrorMemory> lessons) {
        if (lessons == null || lessons.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (DomainModels.AgentErrorMemory lesson : lessons) {
            sb.append(i++).append(". [").append(lesson.errorType()).append("] ");
            if (StringUtils.hasText(lesson.correctionHint())) {
                sb.append(lesson.correctionHint());
            } else {
                sb.append(lesson.errorDetail());
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 人工服务范围非空时为硬约束：剔除范围外服务；若 LLM 全越界则回退为范围内全部服务。
     */
    private ImpactResult enforceScope(ImpactResult impact, ToolContext tools, List<String> trace) {
        if (tools.scope() == null || tools.scope().isEmpty()) {
            return impact;
        }
        List<String> missing = new ArrayList<>(impact.missingEvidence());
        List<DomainModels.ImpactedService> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (DomainModels.ImpactedService item : impact.impactedServices()) {
            if (tools.scope().contains(item.serviceCode())) {
                kept.add(item);
            } else {
                dropped.add(item.serviceCode());
            }
        }
        if (!dropped.isEmpty()) {
            missing.add("已按人工服务范围剔除越界服务：" + String.join("、", dropped));
            if (trace != null) {
                trace.add("范围硬约束：剔除 " + String.join(",", dropped));
            }
        }
        if (kept.isEmpty()) {
            for (String code : tools.scope()) {
                DomainModels.ServiceCatalogItem cat = tools.catalog().get(code);
                if (cat == null) {
                    continue;
                }
                kept.add(new DomainModels.ImpactedService(
                        cat.serviceCode(),
                        cat.serviceName(),
                        "人工选定服务范围内，按需求纳入规划。"
                ));
            }
            missing.add("LLM 影响面均越界，已回退为人工选定的服务范围。");
            if (trace != null) {
                trace.add("范围硬约束：回退为人工选定服务 (" + kept.size() + ")");
            }
        }
        // 范围内但未入选的，提示可能遗漏（不强制扩面）
        Set<String> keptCodes = kept.stream()
                .map(DomainModels.ImpactedService::serviceCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String code : tools.scope()) {
            if (!keptCodes.contains(code) && tools.catalog().containsKey(code)) {
                missing.add("人工范围内未纳入影响面：" + code + "（若确实需要可人工复核）");
            }
        }
        return new ImpactResult(kept, missing);
    }

    private ImpactResult runImpactAgent(
            String title,
            String content,
            ToolContext tools,
            String lessons,
            String reflectionHint
    ) throws Exception {
        boolean hardScope = tools.scope() != null && !tools.scope().isEmpty();
        String catalogForPrompt = hardScope ? tools.scopedCatalogJson() : tools.catalogJson();
        String scopeRule = hardScope
                ? "【硬约束】人工已限定服务范围，impactedServices 只能从下列目录选择，禁止输出范围外 serviceCode。"
                : "【说明】未限定人工范围时，可从完整服务目录中选择。";
        String user = """
                【需求标题】
                %s

                【需求正文】
                %s

                %s

                【工具：可选用服务目录】
                %s

                【工具：人工限定范围】
                %s

                【工具：技术文档检索命中】
                %s

                【历史错误教训（自我修正，可空）】
                %s

                【反思 Agent 纠正提示（可空）】
                %s

                请只输出 JSON。
                """.formatted(
                nullToEmpty(title),
                nullToEmpty(content),
                scopeRule,
                catalogForPrompt,
                tools.scopeJson(),
                tools.evidenceJson(),
                nullToEmpty(lessons),
                nullToEmpty(reflectionHint)
        );
        String raw = chatClient.completeJson(IMPACT_SYSTEM, user, 900);
        JsonNode root = objectMapper.readTree(extractJsonObject(raw));
        List<DomainModels.ImpactedService> impacted = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        JsonNode arr = root.path("impactedServices");
        if (arr.isArray()) {
            for (JsonNode item : arr) {
                String code = item.path("serviceCode").asText("").trim();
                if (!tools.catalog().containsKey(code) || !seen.add(code)) {
                    continue;
                }
                if (hardScope && !tools.scope().contains(code)) {
                    continue;
                }
                DomainModels.ServiceCatalogItem cat = tools.catalog().get(code);
                String reason = item.path("reason").asText("基于需求与技术证据判定需要改动该服务。");
                impacted.add(new DomainModels.ImpactedService(code, cat.serviceName(), reason.trim()));
            }
        }
        return new ImpactResult(impacted, readStringList(root.path("missingEvidence")));
    }

    private DagResult runDagAgent(
            String title,
            String content,
            ToolContext tools,
            ImpactResult impact,
            String previousError,
            String lessons
    ) throws Exception {
        ObjectNode locked = objectMapper.createObjectNode();
        ArrayNode impactedArr = locked.putArray("impactedServices");
        for (DomainModels.ImpactedService item : impact.impactedServices()) {
            ObjectNode n = impactedArr.addObject();
            n.put("serviceCode", item.serviceCode());
            n.put("serviceName", item.serviceName());
            n.put("reason", item.reason());
        }
        locked.set("allowedDependencyEdges", objectMapper.readTree(tools.edgesJson(impact.impactedServices())));
        locked.put("requirementTitle", nullToEmpty(title));
        locked.put("requirementContent", nullToEmpty(content));
        if (StringUtils.hasText(previousError)) {
            locked.put("previousValidationError", previousError);
        }
        if (StringUtils.hasText(lessons)) {
            locked.put("historicalLessons", lessons);
        }

        String user = """
                以下为 ImpactAgent 已锁定的影响面与可用依赖边（只能使用这些服务与边）。
                %s

                请输出任务 DAG JSON。若有 previousValidationError 或 historicalLessons，必须遵守并修正。
                """.formatted(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(locked));

        String raw = chatClient.completeJson(DAG_SYSTEM, user, 1600);
        JsonNode root = objectMapper.readTree(extractJsonObject(raw));
        return parseDag(root, impact, tools);
    }

    private ReviewResult runReviewAgent(
            String title,
            String content,
            ImpactResult impact,
            DagResult dag,
            ToolContext tools,
            String lessons,
            String reflectionHint
    ) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("requirementTitle", nullToEmpty(title));
            payload.put("requirementContent", nullToEmpty(content));
            payload.set("impactedServices", objectMapper.valueToTree(impact.impactedServices()));
            payload.set("tasks", objectMapper.valueToTree(dag.tasks()));
            payload.set("parallelGroups", objectMapper.valueToTree(dag.parallelGroups()));
            payload.set("suggestedReleaseOrder", objectMapper.valueToTree(dag.suggestedReleaseOrder()));
            payload.set("validationSteps", objectMapper.valueToTree(dag.validationSteps()));
            payload.set("reviewChecklist", objectMapper.valueToTree(dag.reviewChecklist()));
            payload.set("dependencyEdgesUsed", objectMapper.readTree(tools.edgesJson(impact.impactedServices())));
            if (StringUtils.hasText(lessons)) {
                payload.put("historicalLessons", lessons);
            }
            if (StringUtils.hasText(reflectionHint)) {
                payload.put("reflectionCorrectionHint", reflectionHint);
            }

            String user = """
                    请对下列规划做评审润色与风险检查，不要改服务集合与 dependsOn。
                    %s
                    """.formatted(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
            String raw = chatClient.completeJson(REVIEW_SYSTEM, user, 1000);
            JsonNode root = objectMapper.readTree(extractJsonObject(raw));
            return new ReviewResult(
                    readStringList(root.path("validationSteps")),
                    readStringList(root.path("reviewChecklist")),
                    blankToNull(root.path("assistSummary").asText(null))
            );
        } catch (Exception ex) {
            log.warn("ReviewAgent failed, keep DagAgent text: {}", ex.getMessage());
            return new ReviewResult(dag.validationSteps(), dag.reviewChecklist(), null);
        }
    }

    private DagResult parseDag(JsonNode root, ImpactResult impact, ToolContext tools) {
        Set<String> allowed = impact.impactedServices().stream()
                .map(DomainModels.ImpactedService::serviceCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, DomainModels.ServiceCatalogItem> catalog = tools.catalog();

        List<DomainModels.AgentTask> tasks = new ArrayList<>();
        JsonNode taskArr = root.path("tasks");
        if (taskArr.isArray()) {
            long autoId = 1;
            for (JsonNode item : taskArr) {
                long taskId = item.path("taskId").asLong(autoId);
                autoId = Math.max(autoId, taskId) + 1;
                String target = item.path("targetService").asText("").trim();
                if (!allowed.contains(target)) {
                    continue;
                }
                String mode = item.path("executionMode").asText("serial").trim().toLowerCase();
                if (!"parallel".equals(mode)) {
                    mode = "serial";
                }
                List<Long> dependsOn = new ArrayList<>();
                JsonNode deps = item.path("dependsOn");
                if (deps.isArray()) {
                    for (JsonNode d : deps) {
                        dependsOn.add(d.asLong());
                    }
                }
                DomainModels.ServiceCatalogItem cat = catalog.get(target);
                tasks.add(new DomainModels.AgentTask(
                        taskId,
                        item.path("taskName").asText("改造 " + target),
                        target,
                        mode,
                        dependsOn,
                        item.path("reason").asText("需要配合需求改造该服务。"),
                        cat == null ? null : cat.ownerTeam(),
                        blankToNull(item.path("dependencyType").asText(null))
                ));
            }
        }

        List<List<String>> parallelGroups = new ArrayList<>();
        JsonNode groups = root.path("parallelGroups");
        if (groups.isArray()) {
            for (JsonNode g : groups) {
                if (!g.isArray()) {
                    continue;
                }
                List<String> row = new ArrayList<>();
                for (JsonNode n : g) {
                    String name = n.asText("").trim();
                    if (StringUtils.hasText(name)) {
                        row.add(name);
                    }
                }
                if (!row.isEmpty()) {
                    parallelGroups.add(row);
                }
            }
        }

        List<String> releaseOrder = new ArrayList<>();
        for (JsonNode n : root.path("suggestedReleaseOrder")) {
            String code = n.asText("").trim();
            if (allowed.contains(code) && !releaseOrder.contains(code)) {
                releaseOrder.add(code);
            }
        }
        for (String code : allowed) {
            if (!releaseOrder.contains(code)) {
                releaseOrder.add(code);
            }
        }

        return new DagResult(
                tasks,
                parallelGroups,
                releaseOrder,
                readStringList(root.path("validationSteps")),
                readStringList(root.path("reviewChecklist"))
        );
    }

    private String validateDag(DagResult dag, ImpactResult impact) {
        if (dag.tasks().isEmpty()) {
            return "tasks 不能为空";
        }
        Set<String> allowed = impact.impactedServices().stream()
                .map(DomainModels.ImpactedService::serviceCode)
                .collect(Collectors.toSet());
        Set<Long> ids = new HashSet<>();
        for (DomainModels.AgentTask task : dag.tasks()) {
            if (!allowed.contains(task.targetService())) {
                return "任务引用了未锁定服务: " + task.targetService();
            }
            if (!ids.add(task.taskId())) {
                return "taskId 重复: " + task.taskId();
            }
        }
        for (DomainModels.AgentTask task : dag.tasks()) {
            for (Long dep : task.dependsOn()) {
                if (!ids.contains(dep)) {
                    return "dependsOn 指向不存在的 taskId=" + dep;
                }
                if (dep.equals(task.taskId())) {
                    return "任务不能依赖自己: " + dep;
                }
            }
        }
        if (hasCycle(dag.tasks())) {
            return "任务依赖存在环";
        }
        return null;
    }

    private boolean hasCycle(List<DomainModels.AgentTask> tasks) {
        Map<Long, List<Long>> graph = new HashMap<>();
        for (DomainModels.AgentTask task : tasks) {
            graph.put(task.taskId(), task.dependsOn() == null ? List.of() : task.dependsOn());
        }
        Set<Long> visiting = new HashSet<>();
        Set<Long> visited = new HashSet<>();
        for (Long id : graph.keySet()) {
            if (dfsCycle(id, graph, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean dfsCycle(Long id, Map<Long, List<Long>> graph, Set<Long> visiting, Set<Long> visited) {
        if (visited.contains(id)) {
            return false;
        }
        if (!visiting.add(id)) {
            return true;
        }
        for (Long next : graph.getOrDefault(id, List.of())) {
            if (dfsCycle(next, graph, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(id);
        visited.add(id);
        return false;
    }

    private ToolContext buildToolContext(
            Set<String> scopedServiceCodes,
            List<KnowledgeBaseService.SearchHit> hits,
            Map<String, DomainModels.ServiceCatalogItem> catalogIndex,
            List<DomainModels.ServiceDependency> dependencyGraph
    ) {
        Map<String, DomainModels.ServiceCatalogItem> catalog = new LinkedHashMap<>(catalogIndex);
        List<DomainModels.AgentEvidenceHit> evidenceHits = new ArrayList<>();
        if (hits != null) {
            for (KnowledgeBaseService.SearchHit hit : hits) {
                String code = hit.document().serviceCode();
                if (StringUtils.hasText(code) && catalog.containsKey(code)) {
                    evidenceHits.add(new DomainModels.AgentEvidenceHit(
                            hit.document().fileName(),
                            code,
                            Math.round(hit.score() * 100.0) / 100.0
                    ));
                }
            }
        }
        return new ToolContext(
                catalog,
                scopedServiceCodes == null ? Set.of() : scopedServiceCodes,
                evidenceHits,
                dependencyGraph,
                objectMapper
        );
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

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static final String IMPACT_SYSTEM = """
            你是「影响面识别 Agent」(ImpactAgent)。根据需求与工具结果，判断需要改哪些微服务。
            只能选择「可选用服务目录」中存在的 serviceCode。
            若提示存在人工硬约束范围，则 impactedServices 必须是该范围的子集，严禁输出范围外服务；
            若需求还依赖范围外服务，写入 missingEvidence 说明，而不是强行加入。
            未限定范围时：优先技术文档命中，其次合理推断。
            若提供历史错误教训或反思纠正提示，必须避免重复同类错误。
            不要输出 Markdown。只输出 JSON：
            {
              "impactedServices":[{"serviceCode":"order-service","reason":"..."}],
              "missingEvidence":["...可选"]
            }
            """;

    private static final String DAG_SYSTEM = """
            你是「任务拆解 Agent」(DagAgent)。在已锁定的影响面内，规划实施任务 DAG。
            回答三个问题：
            1) 每个服务要做什么任务
            2) 哪些任务可并行（executionMode=parallel 且 dependsOn 为空）
            3) 哪些必须串行（dependsOn 指向前置 taskId；优先使用 allowedDependencyEdges）
            禁止新增 impactedServices 之外的服务；禁止环依赖。
            若提供 previousValidationError / historicalLessons，必须修正后重输出。
            只输出 JSON：
            {
              "tasks":[{"taskId":1,"taskName":"...","targetService":"...","executionMode":"serial|parallel","dependsOn":[],"reason":"...","dependencyType":"event|data|api|config"}],
              "parallelGroups":[["任务名A","任务名B"]],
              "suggestedReleaseOrder":["service-code..."],
              "validationSteps":["..."],
              "reviewChecklist":["..."]
            }
            """;

    private static final String REVIEW_SYSTEM = """
            你是「评审 Agent」(ReviewAgent)。在不改服务集合与 dependsOn 的前提下，润色验证步骤与评审清单，并给出 2-4 句规划摘要。
            若提供 historicalLessons 或 reflectionCorrectionHint，请体现对应风险点。
            只输出 JSON：
            {
              "assistSummary":"...",
              "validationSteps":["..."],
              "reviewChecklist":["..."]
            }
            """;

    public record PipelineResult(
            String status,
            List<DomainModels.ImpactedService> impactedServices,
            List<List<String>> parallelGroups,
            List<DomainModels.AgentTask> tasks,
            List<String> validationSteps,
            List<String> missingEvidence,
            List<DomainModels.AgentEvidenceHit> evidenceHits,
            List<DomainModels.ServiceDependency> dependencyEdgesUsed,
            List<String> suggestedReleaseOrder,
            List<String> reviewChecklist,
            String llmAssistSummary,
            String llmAssistStatus,
            String planningMode,
            List<String> agentTrace,
            Integer reflectionRetryCount
    ) {
    }

    private record ImpactResult(
            List<DomainModels.ImpactedService> impactedServices,
            List<String> missingEvidence
    ) {
    }

    private record DagResult(
            List<DomainModels.AgentTask> tasks,
            List<List<String>> parallelGroups,
            List<String> suggestedReleaseOrder,
            List<String> validationSteps,
            List<String> reviewChecklist
    ) {
    }

    private record ReviewResult(
            List<String> validationSteps,
            List<String> reviewChecklist,
            String assistSummary
    ) {
    }

    private record ToolContext(
            Map<String, DomainModels.ServiceCatalogItem> catalog,
            Set<String> scope,
            List<DomainModels.AgentEvidenceHit> evidenceHits,
            List<DomainModels.ServiceDependency> dependencyGraph,
            ObjectMapper objectMapper
    ) {
        String catalogJson() throws Exception {
            ArrayNode arr = objectMapper.createArrayNode();
            for (DomainModels.ServiceCatalogItem item : catalog.values()) {
                ObjectNode n = arr.addObject();
                n.put("serviceCode", item.serviceCode());
                n.put("serviceName", item.serviceName());
                n.put("serviceType", item.serviceType());
                n.put("ownerTeam", item.ownerTeam() == null ? "" : item.ownerTeam());
                n.put("description", item.description() == null ? "" : item.description());
            }
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(arr);
        }

        /** 仅输出人工范围内的服务目录（硬约束时使用）。 */
        String scopedCatalogJson() throws Exception {
            ArrayNode arr = objectMapper.createArrayNode();
            for (String code : scope) {
                DomainModels.ServiceCatalogItem item = catalog.get(code);
                if (item == null) {
                    continue;
                }
                ObjectNode n = arr.addObject();
                n.put("serviceCode", item.serviceCode());
                n.put("serviceName", item.serviceName());
                n.put("serviceType", item.serviceType());
                n.put("ownerTeam", item.ownerTeam() == null ? "" : item.ownerTeam());
                n.put("description", item.description() == null ? "" : item.description());
            }
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(arr);
        }

        String scopeJson() throws Exception {
            return objectMapper.writeValueAsString(scope);
        }

        String evidenceJson() throws Exception {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidenceHits);
        }

        String edgesJson(List<DomainModels.ImpactedService> impacted) throws Exception {
            Set<String> codes = impacted.stream()
                    .map(DomainModels.ImpactedService::serviceCode)
                    .collect(Collectors.toSet());
            List<DomainModels.ServiceDependency> used = dependencyGraph.stream()
                    .filter(e -> codes.contains(e.fromServiceCode()) && codes.contains(e.toServiceCode()))
                    .toList();
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(used);
        }

        List<DomainModels.ServiceDependency> edgesUsed(List<DomainModels.ImpactedService> impacted) {
            Set<String> codes = impacted.stream()
                    .map(DomainModels.ImpactedService::serviceCode)
                    .collect(Collectors.toSet());
            return dependencyGraph.stream()
                    .filter(e -> codes.contains(e.fromServiceCode()) && codes.contains(e.toServiceCode()))
                    .toList();
        }
    }
}
