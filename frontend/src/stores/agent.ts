import { defineStore } from "pinia"
import { ref } from "vue"
import { decomposeRequirement, getAgentPlan, listAgentPlans, listServiceCatalog } from "@/api/agent"
import type {
  AgentPlanCreateResponse,
  AgentPlanDetailView,
  AgentPlanSummaryView,
  ServiceCatalogView
} from "@/api/types"

/**
 * Agent 拆解状态：服务目录、规划历史、提交需求并加载当前规划详情。
 */
export const useAgentStore = defineStore("agent", () => {
  const serviceCatalog = ref<ServiceCatalogView[]>([])
  const planHistory = ref<AgentPlanSummaryView[]>([])
  const currentPlan = ref<AgentPlanDetailView | null>(null)
  const submitting = ref(false)

  /** 进入页面：加载服务目录与历史，并默认打开最近一条规划 */
  async function bootstrap() {
    const [catalog, history] = await Promise.all([
      listServiceCatalog(),
      listAgentPlans()
    ])
    serviceCatalog.value = catalog
    planHistory.value = history
    if (history.length > 0) {
      currentPlan.value = await getAgentPlan(history[0].planId)
    }
  }

  /** 提交拆解后刷新当前规划与历史列表 */
  async function submitRequirement(payload: {
    requirementTitle: string
    requirementContent: string
    serviceCodes: string[]
  }): Promise<AgentPlanCreateResponse> {
    submitting.value = true
    try {
      const result = await decomposeRequirement({
        requirementTitle: payload.requirementTitle,
        requirementContent: payload.requirementContent,
        documentScope: {
          serviceCodes: payload.serviceCodes
        }
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
   * 由 authStore.login / logout 调用，保证 Agent 页不串用户。
   */
  function resetSession() {
    serviceCatalog.value = []
    planHistory.value = []
    currentPlan.value = null
    submitting.value = false
  }

  return {
    serviceCatalog,
    planHistory,
    currentPlan,
    submitting,
    bootstrap,
    submitRequirement,
    selectPlan,
    resetSession
  }
})
