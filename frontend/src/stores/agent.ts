import { defineStore } from "pinia"
import { ref } from "vue"
import {
  decomposeRequirement,
  getAgentPlan,
  listAgentPlans,
  listServiceCatalog,
  listServiceDependencies
} from "@/api/agent"
import type {
  AgentPlanCreateResponse,
  AgentPlanDetailView,
  AgentPlanSummaryView,
  ServiceCatalogView,
  ServiceDependencyView
} from "@/api/types"

/**
 * 研发变更规划状态：服务目录、依赖拓扑、规划历史与当前规划详情。
 */
export const useAgentStore = defineStore("agent", () => {
  const serviceCatalog = ref<ServiceCatalogView[]>([])
  const serviceDependencies = ref<ServiceDependencyView[]>([])
  const planHistory = ref<AgentPlanSummaryView[]>([])
  const currentPlan = ref<AgentPlanDetailView | null>(null)
  const submitting = ref(false)

  /** 进入页面：加载目录、依赖与历史，并默认打开最近一条规划 */
  async function bootstrap() {
    const [catalog, deps, history] = await Promise.all([
      listServiceCatalog(),
      listServiceDependencies(),
      listAgentPlans()
    ])
    serviceCatalog.value = catalog
    serviceDependencies.value = deps
    planHistory.value = history
    if (history.length > 0) {
      currentPlan.value = await getAgentPlan(history[0].planId)
    }
  }

  /** 提交变更规划后刷新当前规划与历史列表 */
  async function submitRequirement(payload: {
    requirementTitle: string
    requirementContent: string
    serviceCodes: string[]
    changeTicketId?: string
    priority?: string
    requester?: string
  }): Promise<AgentPlanCreateResponse> {
    submitting.value = true
    try {
      const result = await decomposeRequirement({
        requirementTitle: payload.requirementTitle,
        requirementContent: payload.requirementContent,
        documentScope: {
          serviceCodes: payload.serviceCodes
        },
        changeTicketId: payload.changeTicketId,
        priority: payload.priority,
        requester: payload.requester
      })
      currentPlan.value = await getAgentPlan(result.planId)
      planHistory.value = await listAgentPlans()
      return result
    } finally {
      submitting.value = false
    }
  }

  /** 按历史记录切换并加载规划详情 */
  async function selectPlan(planId: number) {
    currentPlan.value = await getAgentPlan(planId)
  }

  /**
   * 切换用户时清空规划缓存。
   * 由 authStore.login / logout 调用，保证变更规划页不串用户。
   */
  function resetSession() {
    serviceCatalog.value = []
    serviceDependencies.value = []
    planHistory.value = []
    currentPlan.value = null
    submitting.value = false
  }

  return {
    serviceCatalog,
    serviceDependencies,
    planHistory,
    currentPlan,
    submitting,
    bootstrap,
    submitRequirement,
    selectPlan,
    resetSession
  }
})
