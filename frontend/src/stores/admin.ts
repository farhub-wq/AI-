import { defineStore } from "pinia"
import { ref } from "vue"
import { getDailyQuestionTrend, getFeedbackMetrics, getOverviewMetrics, listAdminConversations } from "@/api/admin"
import type { AdminConversationView, DailyQuestionPointView, FeedbackMetricsView, MetricsOverviewView } from "@/api/types"

/**
 * 管理后台状态：总览指标、反馈质量、日趋势与会话列表的一次性并行加载。
 */
export const useAdminStore = defineStore("admin", () => {
  const overview = ref<MetricsOverviewView | null>(null)
  const feedbackMetrics = ref<FeedbackMetricsView | null>(null)
  const conversations = ref<AdminConversationView[]>([])
  const dailyQuestions = ref<DailyQuestionPointView[]>([])
  const loading = ref(false)

  /** 进入看板：并行拉取总览、反馈、会话与近 7 日趋势 */
  async function bootstrap() {
    loading.value = true
    try {
      const [overviewData, feedbackData, conversationData, dailyData] = await Promise.all([
        getOverviewMetrics(),
        getFeedbackMetrics(),
        listAdminConversations(),
        getDailyQuestionTrend(7)
      ])
      overview.value = overviewData
      feedbackMetrics.value = feedbackData
      conversations.value = conversationData
      dailyQuestions.value = dailyData
    } finally {
      loading.value = false
    }
  }

  return {
    overview,
    feedbackMetrics,
    conversations,
    dailyQuestions,
    loading,
    bootstrap
  }
})
