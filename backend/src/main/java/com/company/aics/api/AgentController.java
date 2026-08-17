package com.company.aics.api;

import com.company.aics.agent.AgentPlannerService;
import com.company.aics.config.AuthenticatedUser;
import com.company.aics.domain.DomainModels;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 研发变更规划 API：变更需求拆解、规划详情/列表、服务目录与依赖查询。
 * 拆解结果含影响服务、并行组、任务 DAG、建议发布顺序与人工评审清单。
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private final AgentPlannerService agentPlannerService;

    /**
     * @param agentPlannerService Agent 规划服务
     */
    public AgentController(AgentPlannerService agentPlannerService) {
        this.agentPlannerService = agentPlannerService;
    }

    /**
     * 提交需求文本与可选服务范围，返回新建规划摘要。
     */
    @PostMapping("/decompose")
    public ApiEnvelope<ApiModels.AgentPlanCreateResponse> decompose(
            Authentication authentication,
            @Valid @RequestBody ApiModels.AgentDecomposeRequest request
    ) {
        AuthenticatedUser currentUser = CurrentUserSupport.require(authentication);
        DomainModels.AgentPlan plan = agentPlannerService.createPlan(
                currentUser.userId(),
                request.requirementTitle(),
                request.requirementContent(),
                request.documentScope() == null ? List.of() : request.documentScope().serviceCodes(),
                request.changeTicketId(),
                request.priority(),
                request.requester()
        );
        return ApiEnvelope.success(new ApiModels.AgentPlanCreateResponse(
                plan.id(),
                plan.status(),
                plan.impactedServices().stream().map(ApiMappers::toImpactedServiceView).toList(),
                plan.parallelGroups(),
                plan.missingEvidence()
        ));
    }

    /**
     * 按 ID 获取当前用户的规划详情。
     */
    @GetMapping("/plans/{planId}")
    public ApiEnvelope<ApiModels.AgentPlanDetailView> getPlan(
            Authentication authentication,
            @PathVariable Long planId
    ) {
        AuthenticatedUser currentUser = CurrentUserSupport.require(authentication);
        return ApiEnvelope.success(ApiMappers.toAgentPlanDetailView(
                agentPlannerService.getPlan(currentUser.userId(), planId)
        ));
    }

    /**
     * 列出服务目录条目。
     */
    @GetMapping("/service-catalog")
    public ApiEnvelope<List<ApiModels.ServiceCatalogView>> serviceCatalog() {
        return ApiEnvelope.success(
                agentPlannerService.listServiceCatalog().stream().map(ApiMappers::toServiceCatalogView).toList()
        );
    }

    /**
     * 列出服务依赖边（事件/数据/API），便于前端理解串行依据。
     */
    @GetMapping("/service-dependencies")
    public ApiEnvelope<List<ApiModels.ServiceDependencyView>> serviceDependencies() {
        return ApiEnvelope.success(
                agentPlannerService.listServiceDependencies().stream().map(ApiMappers::toServiceDependencyView).toList()
        );
    }

    /**
     * 分页列出当前用户的规划摘要。
     */
    @GetMapping("/plans")
    public ApiEnvelope<List<ApiModels.AgentPlanSummaryView>> listPlans(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        AuthenticatedUser currentUser = CurrentUserSupport.require(authentication);
        return ApiEnvelope.success(
                agentPlannerService.listPlans(currentUser.userId(), page, pageSize).stream()
                        .map(ApiMappers::toAgentPlanSummaryView)
                        .toList()
        );
    }
}
