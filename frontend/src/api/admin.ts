import { request } from "./client"
import type { AdminConversationView, DailyQuestionPointView, FeedbackMetricsView, MetricsOverviewView } from "./types"

/**
 * 管理后台 API：总览指标、日趋势、反馈质量与全量会话列表。
 */

/** 拉取运营总览指标（提问量、好评率、兜底率等） */
export function getOverviewMetrics() {
  return request<MetricsOverviewView>("/admin/metrics/overview")
}

/** 拉取近 N 日每日提问量趋势 */
export function getDailyQuestionTrend(days = 7) {
  return request<DailyQuestionPointView[]>(`/admin/metrics/daily-questions?days=${days}`)
}

/** 拉取反馈聚合指标与低分问题列表 */
export function getFeedbackMetrics() {
  return request<FeedbackMetricsView>("/admin/metrics/feedback")
}

/** 分页列出管理端可见的全量会话 */
export function listAdminConversations(page = 1, pageSize = 20) {
  return request<AdminConversationView[]>(`/admin/conversations?page=${page}&pageSize=${pageSize}`)
}
