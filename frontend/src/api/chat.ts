import { request } from "./client"
import type { ConversationDetailView, ConversationSummaryView, FeedbackResponse } from "./types"

/**
 * 会话与反馈 API：创建/列表/详情会话，以及对助手消息提交评分反馈。
 */

/** 创建新会话，可指定标题与默认知识库 */
export function createConversation(payload: { title?: string; kbId?: number }) {
  return request<ConversationSummaryView>("/conversations", {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(payload)
  })
}

/** 分页拉取当前用户的会话摘要列表 */
export function listConversations(page = 1, pageSize = 20) {
  return request<ConversationSummaryView[]>(`/conversations?page=${page}&pageSize=${pageSize}`)
}

/** 拉取指定会话的完整消息详情 */
export function getConversationDetail(conversationId: number) {
  return request<ConversationDetailView>(`/conversations/${conversationId}`)
}

/** 对某条助手消息提交点赞/踩及原因 */
export function submitFeedback(messageId: number, payload: { rating: number; reasonCode: string; comment?: string }) {
  return request<FeedbackResponse>(`/messages/${messageId}/feedback`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(payload)
  })
}
