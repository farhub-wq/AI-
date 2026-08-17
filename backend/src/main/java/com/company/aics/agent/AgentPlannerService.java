package com.company.aics.agent;

import com.company.aics.application.KnowledgeBaseService;
import com.company.aics.domain.DomainModels;
import com.company.aics.persistence.AppDataStore;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 研发变更规划 Agent（README 第 6 点落地）：
 * 优先多 Agent LLM 流水线（Tool 接地 → Impact → Reflection → DAG → Reflection → Review → Reflection）；
 * 错误记忆落库供自我修正；失败则降级规则/检索路径。
 * 与客服 RAG（ChatService）隔离，不改写问答主链路。
 */
@Service
public class AgentPlannerService {

    private static final Logger log = LoggerFactory.getLogger(AgentPlannerService.class);

    private final AppDataStore appDataStore;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AgentPlanLlmAssistant planLlmAssistant;
    private final AgentMultiAgentPipeline multiAgentPipeline;

    public AgentPlannerService(
            AppDataStore appDataStore,
            KnowledgeBaseService knowledgeBaseService,
            AgentPlanLlmAssistant planLlmAssistant,
            AgentMultiAgentPipeline multiAgentPipeline
    ) {
        this.appDataStore = appDataStore;
        this.knowledgeBaseService = knowledgeBaseService;
        this.planLlmAssistant = planLlmAssistant;
        this.multiAgentPipeline = multiAgentPipeline;
    }

    /**
     * 创建规划：优先多 Agent LLM 流水线（Impact→DAG→Review，工具接地）；
     * 失败则降级规则/检索路径，并可再做文案润色。
     */
    public DomainModels.AgentPlan createPlan(
            Long userId,
            String requirementTitle,
            String requirementContent,
            List<String> scopedServiceCodes,
            String changeTicketId,
            String priority,
            String requester
    ) {
        Set<String> scope = new LinkedHashSet<>();
        if (scopedServiceCodes != null) {
            scopedServiceCodes.stream().filter(StringUtils::hasText).forEach(scope::add);
        }

        RequirementSignals signals = parseSignals(requirementTitle, requirementContent);
        String query = signals.searchQuery();
        List<KnowledgeBaseService.SearchHit> hits = knowledgeBaseService.searchTechnicalDocuments(query, scope, 12);
        Map<String, DomainModels.ServiceCatalogItem> catalogIndex = knowledgeBaseService.serviceCatalogIndex();
        List<DomainModels.ServiceDependency> dependencyGraph = appDataStore.listServiceDependencies();

        Optional.ofNullable(signals).ifPresent(s -> {
            if (!s.actions().isEmpty() || !s.sideEffects().isEmpty()) {
                log.info("Agent signals actions={} entities={} sideEffects={}",
                        s.actions(), s.entities(), s.sideEffects());
            }
        });

        var llmPlan = multiAgentPipeline.tryPlan(
                requirementTitle,
                requirementContent,
                scope,
                hits,
                catalogIndex,
                dependencyGraph
        );
        if (llmPlan.isPresent()) {
            AgentMultiAgentPipeline.PipelineResult r = llmPlan.get();
            log.info("Agent plan via multi-agent LLM pipeline, status={}", r.status());
            return appDataStore.saveAgentPlan(new DomainModels.AgentPlan(
                    null,
                    userId,
                    requirementTitle,
                    requirementContent,
                    r.status(),
                    r.impactedServices(),
                    r.parallelGroups(),
                    r.tasks(),
                    r.validationSteps(),
                    r.missingEvidence(),
                    now(),
                    blankToNull(changeTicketId),
                    normalizePriority(priority),
                    blankToNull(requester),
                    List.copyOf(r.evidenceHits()),
                    List.copyOf(r.dependencyEdgesUsed()),
                    r.suggestedReleaseOrder(),
                    r.reviewChecklist(),
                    r.llmAssistSummary(),
                    r.llmAssistStatus(),
                    r.planningMode(),
                    r.agentTrace() == null ? List.of() : r.agentTrace(),
                    r.reflectionRetryCount()
            ));
        }

        log.warn("Multi-agent LLM pipeline unavailable, falling back to rules path");
        return createPlanByRules(
                userId,
                requirementTitle,
                requirementContent,
                scope,
                changeTicketId,
                priority,
                requester,
                signals,
                hits,
                catalogIndex,
                dependencyGraph
        );
    }

    /**
     * 规则/检索降级路径：原启发式打分 + 依赖表建 DAG；可选 LLM 仅润色文案。
     */
    private DomainModels.AgentPlan createPlanByRules(
            Long userId,
            String requirementTitle,
            String requirementContent,
            Set<String> scope,
            String changeTicketId,
            String priority,
            String requester,
            RequirementSignals signals,
            List<KnowledgeBaseService.SearchHit> hits,
            Map<String, DomainModels.ServiceCatalogItem> catalogIndex,
            List<DomainModels.ServiceDependency> dependencyGraph
    ) {
        Map<String, CandidateScore> candidateMap = new LinkedHashMap<>();
        List<DomainModels.AgentEvidenceHit> evidenceHits = new ArrayList<>();
        for (KnowledgeBaseService.SearchHit hit : hits) {
            String serviceCode = hit.document().serviceCode();
            if (!StringUtils.hasText(serviceCode) || !catalogIndex.containsKey(serviceCode)) {
                continue;
            }
            CandidateScore candidate = candidateMap.computeIfAbsent(serviceCode, ignored -> new CandidateScore());
            candidate.score += hit.score();
            candidate.reasons.add("命中技术文档：" + hit.document().fileName());
            evidenceHits.add(new DomainModels.AgentEvidenceHit(
                    hit.document().fileName(),
                    serviceCode,
                    Math.round(hit.score() * 100.0) / 100.0
            ));
        }

        boostBySignals(signals, candidateMap, catalogIndex);

        if (!scope.isEmpty()) {
            for (String serviceCode : scope) {
                if (!catalogIndex.containsKey(serviceCode)) {
                    continue;
                }
                CandidateScore candidate = candidateMap.computeIfAbsent(serviceCode, ignored -> new CandidateScore());
                candidate.score += 0.2;
                candidate.reasons.add("人工选择的服务范围包含该服务");
            }
        }

        List<Map.Entry<String, CandidateScore>> sortedCandidates = candidateMap.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, CandidateScore>>comparingDouble(entry -> entry.getValue().score).reversed())
                .toList();

        List<String> missingEvidence = new ArrayList<>();
        if (hits.isEmpty()) {
            missingEvidence.add("未检索到技术文档证据，请补充服务说明、接口文档或事件文档。");
        }
        missingEvidence.add("规划模式已降级为规则引擎（LLM 多 Agent 不可用或校验未通过）。");

        List<DomainModels.ImpactedService> impactedServices = new ArrayList<>();
        for (Map.Entry<String, CandidateScore> entry : sortedCandidates) {
            if (!scope.isEmpty() && !scope.contains(entry.getKey())) {
                continue;
            }
            DomainModels.ServiceCatalogItem item = catalogIndex.get(entry.getKey());
            impactedServices.add(new DomainModels.ImpactedService(
                    item.serviceCode(),
                    item.serviceName(),
                    String.join("；", entry.getValue().reasons)
            ));
        }

        if (!scope.isEmpty()) {
            Set<String> kept = impactedServices.stream()
                    .map(DomainModels.ImpactedService::serviceCode)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            for (String serviceCode : scope) {
                if (kept.contains(serviceCode)) {
                    continue;
                }
                DomainModels.ServiceCatalogItem item = catalogIndex.get(serviceCode);
                if (item != null) {
                    impactedServices.add(new DomainModels.ImpactedService(
                            item.serviceCode(),
                            item.serviceName(),
                            "人工服务范围硬约束纳入。"
                    ));
                }
            }
            missingEvidence.add("已按人工服务范围硬约束规划（仅范围内服务）。");
        }

        if (impactedServices.isEmpty() && !scope.isEmpty()) {
            for (String serviceCode : scope) {
                DomainModels.ServiceCatalogItem item = catalogIndex.get(serviceCode);
                if (item != null) {
                    impactedServices.add(new DomainModels.ImpactedService(
                            item.serviceCode(),
                            item.serviceName(),
                            "仅由人工服务范围选中，仍需补充更多证据。"
                    ));
                }
            }
            if (!impactedServices.isEmpty()) {
                missingEvidence.add("当前规划主要依赖人工服务范围，请补充技术文档后重试。");
            }
        }

        Set<String> impactedCodes = new LinkedHashSet<>();
        for (DomainModels.ImpactedService item : impactedServices) {
            impactedCodes.add(item.serviceCode());
        }
        List<DomainModels.ServiceDependency> edgesUsed = dependencyGraph.stream()
                .filter(edge -> impactedCodes.contains(edge.fromServiceCode())
                        && impactedCodes.contains(edge.toServiceCode()))
                .toList();

        List<DomainModels.AgentTask> tasks = buildTasks(
                impactedServices, signals, dependencyGraph, catalogIndex, edgesUsed
        );
        List<List<String>> parallelGroups = buildParallelGroups(tasks);
        List<String> validationSteps = buildValidationSteps(impactedServices, signals);
        missingEvidence.addAll(validatePlan(impactedServices, tasks, parallelGroups, dependencyGraph, signals));

        String status = impactedServices.isEmpty()
                ? "failed"
                : (missingEvidence.isEmpty() ? "success" : "partial");

        List<String> suggestedReleaseOrder = buildSuggestedReleaseOrder(impactedServices, tasks);
        List<String> reviewChecklist = buildReviewChecklist(status, impactedServices, edgesUsed, missingEvidence);

        AgentPlanLlmAssistant.AssistResult assist = planLlmAssistant.polish(
                requirementTitle,
                requirementContent,
                impactedServices,
                tasks,
                validationSteps,
                reviewChecklist,
                suggestedReleaseOrder,
                parallelGroups
        );

        return appDataStore.saveAgentPlan(new DomainModels.AgentPlan(
                null,
                userId,
                requirementTitle,
                requirementContent,
                status,
                assist.impactedServices(),
                parallelGroups,
                assist.tasks(),
                assist.validationSteps(),
                missingEvidence,
                now(),
                blankToNull(changeTicketId),
                normalizePriority(priority),
                blankToNull(requester),
                List.copyOf(evidenceHits),
                List.copyOf(edgesUsed),
                suggestedReleaseOrder,
                assist.reviewChecklist(),
                assist.assistSummary(),
                assist.status(),
                AgentMultiAgentPipeline.MODE_RULES_FALLBACK,
                List.of("规则降级路径（LLM 多 Agent / 反思流水线不可用）"),
                0
        ));
    }

    /** 兼容旧调用：无变更单元数据。 */
    public DomainModels.AgentPlan createPlan(
            Long userId,
            String requirementTitle,
            String requirementContent,
            List<String> scopedServiceCodes
    ) {
        return createPlan(userId, requirementTitle, requirementContent, scopedServiceCodes, null, null, null);
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String normalizePriority(String priority) {
        if (!StringUtils.hasText(priority)) {
            return null;
        }
        return priority.trim().toUpperCase(Locale.ROOT);
    }

    public DomainModels.AgentPlan getPlan(Long userId, Long planId) {
        DomainModels.AgentPlan plan = appDataStore.findAgentPlan(planId)
                .orElseThrow(() -> new IllegalArgumentException("规划记录不存在。"));
        if (!Objects.equals(plan.userId(), userId)) {
            throw new IllegalArgumentException("规划记录不存在。");
        }
        return plan;
    }

    public List<DomainModels.AgentPlan> listPlans(Long userId, int page, int pageSize) {
        return appDataStore.listAgentPlansByUser(userId).stream()
                .skip((long) Math.max(page - 1, 0) * pageSize)
                .limit(pageSize)
                .toList();
    }

    public List<DomainModels.ServiceCatalogItem> listServiceCatalog() {
        return appDataStore.listServiceCatalog().stream()
                .sorted(Comparator.comparing(DomainModels.ServiceCatalogItem::serviceCode))
                .toList();
    }

    /** 供前端展示服务依赖目录（事件/数据/API）。 */
    public List<DomainModels.ServiceDependency> listServiceDependencies() {
        return appDataStore.listServiceDependencies();
    }

    /**
     * 轻量语义解析：动作 / 实体 / 副作用信号，驱动检索与启发式（不调用 LLM）。
     */
    RequirementSignals parseSignals(String title, String content) {
        String text = ((title == null ? "" : title) + "\n" + (content == null ? "" : content)).toLowerCase(Locale.ROOT);
        LinkedHashSet<String> actions = new LinkedHashSet<>();
        LinkedHashSet<String> entities = new LinkedHashSet<>();
        LinkedHashSet<String> sideEffects = new LinkedHashSet<>();

        if (containsAny(text, "下单", "订单", "order")) {
            actions.add("place_order");
            entities.add("order");
        }
        if (containsAny(text, "支付", "payment", "付款")) {
            actions.add("pay");
            entities.add("payment");
        }
        if (containsAny(text, "退款", "退货", "refund", "return")) {
            actions.add("refund_or_return");
            entities.add("order");
        }
        if (containsAny(text, "短信", "sms", "通知", "notification", "站内信")) {
            sideEffects.add("notify");
            entities.add("notification");
        }
        if (containsAny(text, "手机号", "mobile", "phone", "用户")) {
            entities.add("user");
        }
        if (containsAny(text, "前端", "页面", "成功页", "frontend", "web", "文案")) {
            sideEffects.add("ui_copy");
            entities.add("frontend");
        }
        if (containsAny(text, "事件", "event", "消息")) {
            entities.add("event");
        }

        String searchQuery = (title == null ? "" : title) + "\n" + (content == null ? "" : content);
        if (!actions.isEmpty() || !entities.isEmpty() || !sideEffects.isEmpty()) {
            searchQuery = searchQuery
                    + "\n动作:" + String.join(",", actions)
                    + "\n实体:" + String.join(",", entities)
                    + "\n副作用:" + String.join(",", sideEffects);
        }
        return new RequirementSignals(List.copyOf(actions), List.copyOf(entities), List.copyOf(sideEffects), searchQuery);
    }

    private void boostBySignals(
            RequirementSignals signals,
            Map<String, CandidateScore> candidateMap,
            Map<String, DomainModels.ServiceCatalogItem> catalogIndex
    ) {
        if (signals.actions().contains("place_order") || signals.entities().contains("order")) {
            boost(candidateMap, catalogIndex, "order-service", 1.0, "语义信号：涉及下单/订单状态");
        }
        if (signals.sideEffects().contains("notify") || signals.entities().contains("notification")) {
            boost(candidateMap, catalogIndex, "notification-service", 1.0, "语义信号：涉及对外通知/短信");
        }
        if (signals.entities().contains("user") || signals.sideEffects().contains("notify")) {
            boost(candidateMap, catalogIndex, "user-service", 0.8, "语义信号：通知链路需要用户资料/手机号");
        }
        if (signals.sideEffects().contains("ui_copy") || signals.entities().contains("frontend")) {
            boost(candidateMap, catalogIndex, "mall-web", 0.8, "语义信号：涉及前端文案或成功页");
        }
        if (signals.actions().contains("refund_or_return")) {
            boost(candidateMap, catalogIndex, "order-service", 0.6, "语义信号：涉及退款/退货状态");
            boost(candidateMap, catalogIndex, "notification-service", 0.5, "语义信号：退款结果可能需通知用户");
        }
    }

    private void boost(
            Map<String, CandidateScore> candidateMap,
            Map<String, DomainModels.ServiceCatalogItem> catalogIndex,
            String serviceCode,
            double delta,
            String reason
    ) {
        if (!catalogIndex.containsKey(serviceCode)) {
            return;
        }
        CandidateScore candidate = candidateMap.computeIfAbsent(serviceCode, ignored -> new CandidateScore());
        candidate.score += delta;
        candidate.reasons.add(reason);
    }

    /**
     * 两阶段建任务：先为每个受影响服务建节点，再按依赖表与领域规则挂边（避免排序导致 dependsOn 丢失）。
     */
    private List<DomainModels.AgentTask> buildTasks(
            List<DomainModels.ImpactedService> impactedServices,
            RequirementSignals signals,
            List<DomainModels.ServiceDependency> dependencyGraph,
            Map<String, DomainModels.ServiceCatalogItem> catalogIndex,
            List<DomainModels.ServiceDependency> edgesUsed
    ) {
        Map<String, Long> serviceTaskIds = new LinkedHashMap<>();
        Map<String, MutableTask> draftByService = new LinkedHashMap<>();

        for (DomainModels.ImpactedService impacted : impactedServices) {
            String code = impacted.serviceCode();
            long taskId = appDataStore.nextAgentTaskId();
            serviceTaskIds.put(code, taskId);
            DomainModels.ServiceCatalogItem catalog = catalogIndex.get(code);
            String ownerTeam = catalog == null ? null : catalog.ownerTeam();
            draftByService.put(code, createDraftTask(taskId, code, signals, ownerTeam));
        }

        // 基于服务依赖表：from → to 表示 to 依赖 from 先完成
        for (DomainModels.ServiceDependency edge : dependencyGraph) {
            Long fromTask = serviceTaskIds.get(edge.fromServiceCode());
            Long toTask = serviceTaskIds.get(edge.toServiceCode());
            if (fromTask == null || toTask == null) {
                continue;
            }
            MutableTask toDraft = draftByService.get(edge.toServiceCode());
            if (toDraft == null) {
                continue;
            }
            if (!toDraft.dependsOn.contains(fromTask)) {
                toDraft.dependsOn.add(fromTask);
            }
            if (!StringUtils.hasText(toDraft.dependencyType)) {
                toDraft.dependencyType = edge.dependencyType();
            }
            if (!"order-service".equals(edge.toServiceCode()) || !toDraft.dependsOn.isEmpty()) {
                if ("event".equalsIgnoreCase(edge.dependencyType())
                        || "data".equalsIgnoreCase(edge.dependencyType())
                        || "api".equalsIgnoreCase(edge.dependencyType())) {
                    if (!"mall-web".equals(edge.toServiceCode()) || "event".equalsIgnoreCase(edge.dependencyType())) {
                        if (!"mall-web".equals(edge.toServiceCode())) {
                            toDraft.executionMode = "serial";
                        }
                    }
                    toDraft.reason = toDraft.reason + "；依赖边[" + edge.dependencyType() + "]："
                            + edge.fromServiceCode() + " → " + edge.toServiceCode();
                }
            }
        }

        // 领域兜底：通知必须挂订单事件与用户数据（即便依赖表缺失）
        if (draftByService.containsKey("notification-service")) {
            MutableTask notify = draftByService.get("notification-service");
            notify.executionMode = "serial";
            if (serviceTaskIds.containsKey("order-service") && !notify.dependsOn.contains(serviceTaskIds.get("order-service"))) {
                notify.dependsOn.add(serviceTaskIds.get("order-service"));
            }
            if (serviceTaskIds.containsKey("user-service") && !notify.dependsOn.contains(serviceTaskIds.get("user-service"))) {
                notify.dependsOn.add(serviceTaskIds.get("user-service"));
            }
            if (!StringUtils.hasText(notify.dependencyType)) {
                notify.dependencyType = "event";
            }
        }
        if (draftByService.containsKey("order-service")) {
            draftByService.get("order-service").executionMode = "serial";
        }
        if (draftByService.containsKey("user-service")) {
            MutableTask user = draftByService.get("user-service");
            if (user.dependsOn.isEmpty()) {
                user.executionMode = "parallel";
            }
        }
        if (draftByService.containsKey("mall-web")) {
            MutableTask web = draftByService.get("mall-web");
            web.dependsOn.clear();
            web.executionMode = "parallel";
            web.dependencyType = "config";
            web.reason = "前端成功页文案可与后端契约/手机号能力并行推进。";
        }

        List<DomainModels.AgentTask> tasks = new ArrayList<>();
        for (DomainModels.ImpactedService impacted : impactedServices) {
            MutableTask draft = draftByService.get(impacted.serviceCode());
            if (draft != null) {
                tasks.add(draft.toImmutable());
            }
        }

        if (!tasks.isEmpty()) {
            List<Long> dependsOnAll = tasks.stream().map(DomainModels.AgentTask::taskId).toList();
            tasks.add(new DomainModels.AgentTask(
                    appDataStore.nextAgentTaskId(),
                    "联调与端到端验收",
                    tasks.getLast().targetService(),
                    "serial",
                    dependsOnAll,
                    "统一验证改动服务、依赖顺序、通知送达与界面表现。",
                    "平台联调",
                    edgesUsed.isEmpty() ? null : "api"
            ));
        }
        return tasks;
    }

    private MutableTask createDraftTask(
            long taskId,
            String serviceCode,
            RequirementSignals signals,
            String ownerTeam
    ) {
        boolean sms = signals.sideEffects().contains("notify");
        return switch (serviceCode) {
            case "order-service" -> new MutableTask(
                    taskId,
                    sms ? "定义订单成功事件载荷" : "调整订单侧接口或事件契约",
                    serviceCode,
                    "serial",
                    new ArrayList<>(),
                    "上游订单契约应先稳定，再启动下游改造。",
                    ownerTeam,
                    null
            );
            case "notification-service" -> new MutableTask(
                    taskId,
                    sms ? "实现短信通知消费流程" : "实现通知消费与发送流程",
                    serviceCode,
                    "serial",
                    new ArrayList<>(),
                    "通知发送依赖上游事件载荷与收件人数据。",
                    ownerTeam,
                    "event"
            );
            case "user-service" -> new MutableTask(
                    taskId,
                    "开放并校验手机号查询",
                    serviceCode,
                    "parallel",
                    new ArrayList<>(),
                    "发送链路必须能获取用户手机号。",
                    ownerTeam,
                    "data"
            );
            case "mall-web" -> new MutableTask(
                    taskId,
                    "更新前端成功状态文案",
                    serviceCode,
                    "parallel",
                    new ArrayList<>(),
                    "前端文案与状态展示通常可并行推进。",
                    ownerTeam,
                    "config"
            );
            default -> new MutableTask(
                    taskId,
                    "评审并更新" + serviceCode,
                    serviceCode,
                    "serial",
                    new ArrayList<>(),
                    "需要补充更多服务级证据后再细化实施步骤。",
                    ownerTeam,
                    null
            );
        };
    }

    /**
     * 建议发布顺序：无依赖服务可先上（并行组），再按 dependsOn 深度拓扑；联调任务不计入服务发布序。
     */
    private List<String> buildSuggestedReleaseOrder(
            List<DomainModels.ImpactedService> impactedServices,
            List<DomainModels.AgentTask> tasks
    ) {
        Map<String, DomainModels.AgentTask> byService = new LinkedHashMap<>();
        for (DomainModels.AgentTask task : tasks) {
            if ("联调与端到端验收".equals(task.taskName())) {
                continue;
            }
            byService.putIfAbsent(task.targetService(), task);
        }
        Map<Long, DomainModels.AgentTask> byId = new LinkedHashMap<>();
        for (DomainModels.AgentTask task : tasks) {
            byId.put(task.taskId(), task);
        }
        List<String> ordered = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        // 多轮：把 dependsOn 均已放入的服务依次加入
        boolean progressed = true;
        while (progressed && placed.size() < byService.size()) {
            progressed = false;
            for (DomainModels.ImpactedService impacted : impactedServices) {
                String code = impacted.serviceCode();
                if (placed.contains(code) || !byService.containsKey(code)) {
                    continue;
                }
                DomainModels.AgentTask task = byService.get(code);
                boolean ready = true;
                for (Long depId : task.dependsOn()) {
                    DomainModels.AgentTask dep = byId.get(depId);
                    if (dep == null) {
                        continue;
                    }
                    if ("联调与端到端验收".equals(dep.taskName())) {
                        continue;
                    }
                    if (!placed.contains(dep.targetService())) {
                        ready = false;
                        break;
                    }
                }
                if (ready) {
                    ordered.add(code);
                    placed.add(code);
                    progressed = true;
                }
            }
        }
        for (DomainModels.ImpactedService impacted : impactedServices) {
            if (!placed.contains(impacted.serviceCode())) {
                ordered.add(impacted.serviceCode());
            }
        }
        return ordered;
    }

    /** 生产向人工评审清单。 */
    private List<String> buildReviewChecklist(
            String status,
            List<DomainModels.ImpactedService> impactedServices,
            List<DomainModels.ServiceDependency> edgesUsed,
            List<String> missingEvidence
    ) {
        List<String> checklist = new ArrayList<>();
        checklist.add("确认影响面服务列表与业务方/架构师对齐（共 "
                + impactedServices.size() + " 个服务）。");
        checklist.add("复核依赖边是否完整（本计划引用 " + edgesUsed.size() + " 条依赖）。");
        checklist.add("按「建议发布顺序」安排合并与灰度，禁止下游先于上游合入强依赖契约。");
        checklist.add("准备回滚点：事件契约、短信开关、前端文案开关。");
        checklist.add("联调通过后再关闭变更单（含端到端验收任务）。");
        if (!missingEvidence.isEmpty() || "partial".equals(status) || "failed".equals(status)) {
            checklist.add("存在缺失证据或校验提示：补齐技术文档/接口说明后再合入主干。");
        }
        return checklist;
    }

    private List<List<String>> buildParallelGroups(List<DomainModels.AgentTask> tasks) {
        List<String> group = tasks.stream()
                .filter(task -> "parallel".equalsIgnoreCase(task.executionMode()))
                .filter(task -> task.dependsOn().isEmpty())
                .map(DomainModels.AgentTask::taskName)
                .toList();
        return group.isEmpty() ? List.of() : List.of(group);
    }

    private List<String> buildValidationSteps(
            List<DomainModels.ImpactedService> impactedServices,
            RequirementSignals signals
    ) {
        List<String> steps = new ArrayList<>();
        if (impactedServices.stream().anyMatch(item -> "order-service".equals(item.serviceCode()))) {
            steps.add("验证订单服务已发布或暴露更新后的契约（如 order.created）。");
        }
        if (impactedServices.stream().anyMatch(item -> "notification-service".equals(item.serviceCode()))) {
            steps.add(signals.sideEffects().contains("notify")
                    ? "验证通知服务能消费事件并完成短信发送。"
                    : "验证通知服务能消费事件并完成发送。");
        }
        if (impactedServices.stream().anyMatch(item -> "user-service".equals(item.serviceCode()))) {
            steps.add("验证手机号查询与校验规则可用。");
        }
        if (impactedServices.stream().anyMatch(item -> "mall-web".equals(item.serviceCode()))) {
            steps.add("验证前端成功页展示预期结果文案。");
        }
        if (steps.isEmpty()) {
            steps.add("补充更多技术证据后重新执行拆解。");
        } else {
            steps.add("按任务 dependsOn 顺序联调，确认无可并行误判的强依赖。");
        }
        return steps;
    }

    /**
     * 后校验：强依赖不得进入并行组；通知缺上游则记入缺失证据；简单环检测。
     */
    private List<String> validatePlan(
            List<DomainModels.ImpactedService> impactedServices,
            List<DomainModels.AgentTask> tasks,
            List<List<String>> parallelGroups,
            List<DomainModels.ServiceDependency> dependencyGraph,
            RequirementSignals signals
    ) {
        List<String> issues = new ArrayList<>();
        Set<String> impactedCodes = new LinkedHashSet<>();
        for (DomainModels.ImpactedService item : impactedServices) {
            impactedCodes.add(item.serviceCode());
        }

        Map<Long, DomainModels.AgentTask> byId = new LinkedHashMap<>();
        for (DomainModels.AgentTask task : tasks) {
            byId.put(task.taskId(), task);
        }

        Set<String> parallelNames = new LinkedHashSet<>();
        for (List<String> group : parallelGroups) {
            parallelNames.addAll(group);
        }
        for (DomainModels.AgentTask task : tasks) {
            if (parallelNames.contains(task.taskName()) && !task.dependsOn().isEmpty()) {
                issues.add("校验失败：任务「" + task.taskName() + "」存在依赖却进入并行组。");
            }
        }

        if (signals.sideEffects().contains("notify")) {
            if (impactedCodes.contains("notification-service") && !impactedCodes.contains("order-service")) {
                issues.add("校验提示：通知改造通常需要订单侧事件契约，当前未召回 order-service。");
            }
            if (impactedCodes.contains("notification-service") && !impactedCodes.contains("user-service")) {
                issues.add("校验提示：短信发送通常需要用户手机号能力，当前未召回 user-service。");
            }
            DomainModels.AgentTask notifyTask = tasks.stream()
                    .filter(task -> "notification-service".equals(task.targetService()))
                    .filter(task -> !task.taskName().contains("联调"))
                    .findFirst()
                    .orElse(null);
            if (notifyTask != null && notifyTask.dependsOn().isEmpty()) {
                issues.add("校验失败：通知任务缺少上游 dependsOn，串行关系可能丢失。");
            }
        }

        // 依赖表中两端都在影响集，但任务边缺失时提示
        Map<String, Long> serviceToTask = new LinkedHashMap<>();
        for (DomainModels.AgentTask task : tasks) {
            if (!task.taskName().contains("联调")) {
                serviceToTask.putIfAbsent(task.targetService(), task.taskId());
            }
        }
        for (DomainModels.ServiceDependency edge : dependencyGraph) {
            if (!impactedCodes.contains(edge.fromServiceCode()) || !impactedCodes.contains(edge.toServiceCode())) {
                continue;
            }
            if ("config".equalsIgnoreCase(edge.dependencyType())) {
                continue;
            }
            Long fromId = serviceToTask.get(edge.fromServiceCode());
            Long toId = serviceToTask.get(edge.toServiceCode());
            if (fromId == null || toId == null) {
                continue;
            }
            // mall-web 展示依赖允许不挂硬边
            if ("mall-web".equals(edge.toServiceCode())) {
                continue;
            }
            DomainModels.AgentTask toTask = byId.get(toId);
            if (toTask != null && !toTask.dependsOn().contains(fromId)) {
                issues.add("校验提示：服务依赖 " + edge.fromServiceCode() + "→" + edge.toServiceCode()
                        + "（" + edge.dependencyType() + "）未完整体现为任务边。");
            }
        }

        if (hasCycle(tasks)) {
            issues.add("校验失败：任务依赖图存在环，请检查服务依赖配置。");
        }
        return issues;
    }

    private boolean hasCycle(List<DomainModels.AgentTask> tasks) {
        Map<Long, List<Long>> graph = new LinkedHashMap<>();
        for (DomainModels.AgentTask task : tasks) {
            graph.put(task.taskId(), task.dependsOn());
        }
        Set<Long> visiting = new LinkedHashSet<>();
        Set<Long> visited = new LinkedHashSet<>();
        for (Long node : graph.keySet()) {
            if (dfsCycle(node, graph, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean dfsCycle(
            Long node,
            Map<Long, List<Long>> graph,
            Set<Long> visiting,
            Set<Long> visited
    ) {
        if (visited.contains(node)) {
            return false;
        }
        if (!visiting.add(node)) {
            return true;
        }
        for (Long parent : graph.getOrDefault(node, List.of())) {
            // dependsOn 指向前置任务：边方向 parent -> node，环检测沿前置回溯
            if (dfsCycle(parent, graph, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(node);
        visited.add(node);
        return false;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.ofHours(8));
    }

    record RequirementSignals(
            List<String> actions,
            List<String> entities,
            List<String> sideEffects,
            String searchQuery
    ) {
    }

    private static final class CandidateScore {
        private double score;
        private final Set<String> reasons = new LinkedHashSet<>();
    }

    private static final class MutableTask {
        private final long taskId;
        private String taskName;
        private final String targetService;
        private String executionMode;
        private final List<Long> dependsOn;
        private String reason;
        private String ownerTeam;
        private String dependencyType;

        private MutableTask(
                long taskId,
                String taskName,
                String targetService,
                String executionMode,
                List<Long> dependsOn,
                String reason,
                String ownerTeam,
                String dependencyType
        ) {
            this.taskId = taskId;
            this.taskName = taskName;
            this.targetService = targetService;
            this.executionMode = executionMode;
            this.dependsOn = dependsOn;
            this.reason = reason;
            this.ownerTeam = ownerTeam;
            this.dependencyType = dependencyType;
        }

        private DomainModels.AgentTask toImmutable() {
            return new DomainModels.AgentTask(
                    taskId,
                    taskName,
                    targetService,
                    executionMode,
                    List.copyOf(dependsOn),
                    reason,
                    ownerTeam,
                    dependencyType
            );
        }
    }
}
