import { request } from "./client"
import type {
  AgentPlanCreateResponse,
  AgentPlanDetailView,
  AgentPlanSummaryView,
  ServiceCatalogView,
  ServiceDependencyView
} from "./types"

/**
 * 研发变更规划 API：提交拆解、查询规划详情/历史、服务目录与依赖拓扑。
 */

/** 提交变更需求（含可选变更单元数据），触发拆解并返回规划摘要 */
export function decomposeRequirement(payload: {
  requirementTitle: string
  requirementContent: string
  documentScope: {
    serviceCodes: string[]
  }
  changeTicketId?: string
  priority?: string
  requester?: string
}) {
  return request<AgentPlanCreateResponse>("/agent/decompose", {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(payload)
  })
}

/** 按 planId 拉取完整规划详情 */
export function getAgentPlan(planId: number) {
  return request<AgentPlanDetailView>(`/agent/plans/${planId}`)
}

/** 分页列出历史拆解规划摘要 */
export function listAgentPlans(page = 1, pageSize = 20) {
  return request<AgentPlanSummaryView[]>(`/agent/plans?page=${page}&pageSize=${pageSize}`)
}

/** 拉取可供选择的微服务目录 */
export function listServiceCatalog() {
  return request<ServiceCatalogView[]>("/agent/service-catalog")
}

/** 拉取服务依赖边（事件/数据/API），作为串行依据 */
export function listServiceDependencies() {
  return request<ServiceDependencyView[]>("/agent/service-dependencies")
}
