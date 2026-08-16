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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Agent 规划服务：基于技术文档检索与关键词启发，拆解需求为受影响服务、任务、并行组与验收步骤。
 * 结果写入 MySQL（{@link AppDataStore}），状态为 success / partial / failed，取决于证据是否充足。
 */
@Service
public class AgentPlannerService {

    private final AppDataStore appDataStore;
    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * @param appDataStore         MySQL 数据访问门面
     * @param knowledgeBaseService 知识库检索服务
     */
    public AgentPlannerService(AppDataStore appDataStore, KnowledgeBaseService knowledgeBaseService) {
        this.appDataStore = appDataStore;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /**
     * 创建规划：检索技术文档 → 候选服务打分 → 启发加权 → 生成任务与校验步骤并落库。
     */
    public DomainModels.AgentPlan createPlan(
            Long userId,
            String requirementTitle,
            String requirementContent,
            List<String> scopedServiceCodes
    ) {
        Set<String> scope = new LinkedHashSet<>();
        if (scopedServiceCodes != null) {
            scopedServiceCodes.stream().filter(StringUtils::hasText).forEach(scope::add);
        }

        String query = requirementTitle + "\n" + requirementContent;
        List<KnowledgeBaseService.SearchHit> hits = knowledgeBaseService.searchTechnicalDocuments(query, scope, 12);
        Map<String, DomainModels.ServiceCatalogItem> catalogIndex = knowledgeBaseService.serviceCatalogIndex();
        Map<String, CandidateScore> candidateMap = new LinkedHashMap<>();

        // 按命中文档的 serviceCode 累加检索分数与理由
        for (KnowledgeBaseService.SearchHit hit : hits) {
            String serviceCode = hit.document().serviceCode();
            if (!StringUtils.hasText(serviceCode) || !catalogIndex.containsKey(serviceCode)) {
                continue;
            }
            CandidateScore candidate = candidateMap.computeIfAbsent(serviceCode, ignored -> new CandidateScore());
            candidate.score += hit.score();
            candidate.reasons.add("命中文档：" + hit.document().fileName());
        }

        boostByHeuristics(requirementTitle + "\n" + requirementContent, candidateMap, catalogIndex);

        // 人工选定范围额外加权，避免检索漏召回
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

        List<DomainModels.ImpactedService> impactedServices = new ArrayList<>();
        for (Map.Entry<String, CandidateScore> entry : sortedCandidates) {
            DomainModels.ServiceCatalogItem item = catalogIndex.get(entry.getKey());
            impactedServices.add(new DomainModels.ImpactedService(
                    item.serviceCode(),
                    item.serviceName(),
                    String.join("；", entry.getValue().reasons)
            ));
        }

        // 无检索命中但有人工范围时，仍输出占位受影响服务并标记证据不足
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

        List<DomainModels.AgentTask> tasks = buildTasks(impactedServices, requirementContent);
        List<List<String>> parallelGroups = buildParallelGroups(tasks);
        List<String> validationSteps = buildValidationSteps(impactedServices);
        String status = impactedServices.isEmpty()
                ? "failed"
                : (missingEvidence.isEmpty() ? "success" : "partial");

        return appDataStore.saveAgentPlan(new DomainModels.AgentPlan(
                null,
                userId,
                requirementTitle,
                requirementContent,
                status,
                impactedServices,
                parallelGroups,
                tasks,
                validationSteps,
                missingEvidence,
                now()
        ));
    }

    /**
     * 按用户归属获取规划详情。
     */
    public DomainModels.AgentPlan getPlan(Long userId, Long planId) {
        DomainModels.AgentPlan plan = appDataStore.findAgentPlan(planId)
                .orElseThrow(() -> new IllegalArgumentException("规划记录不存在。"));
        if (!Objects.equals(plan.userId(), userId)) {
            throw new IllegalArgumentException("规划记录不存在。");
        }
        return plan;
    }

    /**
     * 分页列出当前用户的规划（按创建时间倒序）。
     */
    public List<DomainModels.AgentPlan> listPlans(Long userId, int page, int pageSize) {
        return appDataStore.listAgentPlansByUser(userId).stream()
                .skip((long) Math.max(page - 1, 0) * pageSize)
                .limit(pageSize)
                .toList();
    }

    /**
     * 列出服务目录（按 serviceCode 排序）。
     */
    public List<DomainModels.ServiceCatalogItem> listServiceCatalog() {
        return appDataStore.listServiceCatalog().stream()
                .sorted(Comparator.comparing(DomainModels.ServiceCatalogItem::serviceCode))
                .toList();
    }

    /**
     * 根据需求文本中的业务关键词对候选服务加分。
     */
    private void boostByHeuristics(
            String requirementText,
            Map<String, CandidateScore> candidateMap,
            Map<String, DomainModels.ServiceCatalogItem> catalogIndex
    ) {
        boostIfContains(requirementText, List.of("order", "orders", "下单", "订单"), "order-service", "需求涉及下单或订单状态", candidateMap, catalogIndex);
        boostIfContains(requirementText, List.of("sms", "notification", "通知", "短信"), "notification-service", "需求涉及对外通知发送", candidateMap, catalogIndex);
        boostIfContains(requirementText, List.of("phone", "mobile", "user", "手机号", "用户"), "user-service", "需求依赖用户资料或手机号", candidateMap, catalogIndex);
        boostIfContains(requirementText, List.of("frontend", "page", "web", "前端", "页面", "提示"), "mall-web", "需求涉及前端文案或交互", candidateMap, catalogIndex);
    }

    /**
     * 文本命中任一关键词时，为指定服务增加 1.0 分并记录理由。
     */
    private void boostIfContains(
            String text,
            List<String> keywords,
            String serviceCode,
            String reason,
            Map<String, CandidateScore> candidateMap,
            Map<String, DomainModels.ServiceCatalogItem> catalogIndex
    ) {
        if (!catalogIndex.containsKey(serviceCode)) {
            return;
        }
        boolean matched = keywords.stream().anyMatch(text::contains);
        if (matched) {
            CandidateScore candidate = candidateMap.computeIfAbsent(serviceCode, ignored -> new CandidateScore());
            candidate.score += 1.0;
            candidate.reasons.add(reason);
        }
    }

    /**
     * 按受影响服务生成实施任务，并追加端到端联调验收任务。
     */
    private List<DomainModels.AgentTask> buildTasks(List<DomainModels.ImpactedService> impactedServices, String requirementContent) {
        List<DomainModels.AgentTask> tasks = new ArrayList<>();
        Map<String, Long> taskIds = new LinkedHashMap<>();
        String loweredRequirement = requirementContent.toLowerCase();

        for (DomainModels.ImpactedService impactedService : impactedServices) {
            String serviceCode = impactedService.serviceCode();
            DomainModels.AgentTask task;

            // 订单服务通常作为上游契约，串行优先稳定
            if ("order-service".equals(serviceCode)) {
                task = new DomainModels.AgentTask(
                        appDataStore.nextAgentTaskId(),
                        loweredRequirement.contains("sms") || requirementContent.contains("短信")
                                ? "定义订单成功事件载荷"
                                : "调整订单侧接口或事件契约",
                        serviceCode,
                        "serial",
                        List.of(),
                        "上游订单契约应先稳定，再启动下游改造。"
                );
            } else if ("notification-service".equals(serviceCode)) {
                // 通知依赖订单事件与用户手机号
                List<Long> dependsOn = new ArrayList<>();
                if (taskIds.containsKey("order-service")) {
                    dependsOn.add(taskIds.get("order-service"));
                }
                if (taskIds.containsKey("user-service")) {
                    dependsOn.add(taskIds.get("user-service"));
                }
                task = new DomainModels.AgentTask(
                        appDataStore.nextAgentTaskId(),
                        loweredRequirement.contains("sms") || requirementContent.contains("短信")
                                ? "实现短信通知消费流程"
                                : "实现通知消费与发送流程",
                        serviceCode,
                        "serial",
                        dependsOn,
                        "通知发送依赖上游事件载荷与收件人数据。"
                );
            } else if ("user-service".equals(serviceCode)) {
                task = new DomainModels.AgentTask(
                        appDataStore.nextAgentTaskId(),
                        "开放并校验手机号查询",
                        serviceCode,
                        "parallel",
                        List.of(),
                        "发送链路必须能获取用户手机号。"
                );
            } else if ("mall-web".equals(serviceCode)) {
                task = new DomainModels.AgentTask(
                        appDataStore.nextAgentTaskId(),
                        "更新前端成功状态文案",
                        serviceCode,
                        "parallel",
                        List.of(),
                        "前端文案与状态展示通常可并行推进。"
                );
            } else {
                task = new DomainModels.AgentTask(
                        appDataStore.nextAgentTaskId(),
                        "评审并更新" + impactedService.serviceName(),
                        serviceCode,
                        "serial",
                        List.of(),
                        "需要补充更多服务级证据后再细化实施步骤。"
                );
            }

            tasks.add(task);
            taskIds.put(serviceCode, task.taskId());
        }

        // 末尾统一联调任务依赖此前全部任务
        if (!tasks.isEmpty()) {
            List<Long> dependsOn = tasks.stream().map(DomainModels.AgentTask::taskId).toList();
            tasks.add(new DomainModels.AgentTask(
                    appDataStore.nextAgentTaskId(),
                    "联调与端到端验收",
                    tasks.getLast().targetService(),
                    "serial",
                    dependsOn,
                    "统一验证改动服务、依赖顺序与界面表现。"
            ));
        }

        return tasks;
    }

    /**
     * 收集可并行且无依赖的任务名，组成并行组列表。
     */
    private List<List<String>> buildParallelGroups(List<DomainModels.AgentTask> tasks) {
        List<String> group = tasks.stream()
                .filter(task -> "parallel".equalsIgnoreCase(task.executionMode()))
                .filter(task -> task.dependsOn().isEmpty())
                .map(DomainModels.AgentTask::taskName)
                .toList();
        return group.isEmpty() ? List.of() : List.of(group);
    }

    /**
     * 按受影响服务生成对应验收步骤清单。
     */
    private List<String> buildValidationSteps(List<DomainModels.ImpactedService> impactedServices) {
        List<String> steps = new ArrayList<>();
        if (impactedServices.stream().anyMatch(item -> "order-service".equals(item.serviceCode()))) {
            steps.add("验证订单服务已发布或暴露更新后的契约。");
        }
        if (impactedServices.stream().anyMatch(item -> "notification-service".equals(item.serviceCode()))) {
            steps.add("验证通知服务能消费事件并完成发送。");
        }
        if (impactedServices.stream().anyMatch(item -> "user-service".equals(item.serviceCode()))) {
            steps.add("验证手机号查询与校验规则。");
        }
        if (impactedServices.stream().anyMatch(item -> "mall-web".equals(item.serviceCode()))) {
            steps.add("验证前端成功页展示预期结果文案。");
        }
        if (steps.isEmpty()) {
            steps.add("补充更多技术证据后重新执行拆解。");
        }
        return steps;
    }

    /** @return 东八区当前时间 */
    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.ofHours(8));
    }

    /** 候选服务累计分数与命中理由。 */
    private static final class CandidateScore {
        private double score;
        private final Set<String> reasons = new LinkedHashSet<>();
    }
}
