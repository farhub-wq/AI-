import { defineStore } from "pinia"
import { computed, ref } from "vue"
import { createConversation, getConversationDetail, listConversations, submitFeedback } from "@/api/chat"
import { listKnowledgeBases } from "@/api/knowledge"
import type { CitationView, ConversationDetailView, ConversationSummaryView, KnowledgeBaseView, MessageView } from "@/api/types"
import { startChatStream } from "@/composables/useChatStream"

/**
 * 对话状态：知识库/会话列表、当前会话消息、SSE 流式草稿与追问建议。
 */

/** 流式输出过程中的临时助手消息草稿 */
interface StreamDraft {
  messageId: number | null
  content: string
  citations: CitationView[]
  answerStatus: string
  intentLabel?: string | null
}

export const useChatStore = defineStore("chat", () => {
  const knowledgeBases = ref<KnowledgeBaseView[]>([])
  const conversations = ref<ConversationSummaryView[]>([])
  const currentConversation = ref<ConversationDetailView | null>(null)
  const activeKbId = ref<number>(0)
  const streaming = ref(false)
  const streamDraft = ref<StreamDraft | null>(null)
  const latestFollowUpSuggestions = ref<string[]>([])
  const loading = ref(false)

  /** 当前展示消息：已落库消息 + 流式草稿（若有） */
  const currentMessages = computed<MessageView[]>(() => {
    const base = currentConversation.value?.messages ?? []
    if (!streamDraft.value) {
      return base
    }
    // 将流式草稿拼成一条临时助手消息，便于 UI 即时渲染
    const draft: MessageView = {
      id: streamDraft.value.messageId ?? -1,
      role: "assistant",
      content: streamDraft.value.content,
      citations: streamDraft.value.citations,
      answerStatus: streamDraft.value.answerStatus,
      intentLabel: streamDraft.value.intentLabel,
      createdAt: new Date().toISOString()
    }
    return [...base, draft]
  })

  /** 进入对话页：并行加载知识库与会话，并默认选中首条会话 */
  async function bootstrap() {
    loading.value = true
    try {
      const [kbList, conversationList] = await Promise.all([
        listKnowledgeBases(),
        listConversations()
      ])
      knowledgeBases.value = kbList
      conversations.value = conversationList
      // 默认自动路由（kbId=0）；若已有会话则跟随会话绑定库
      activeKbId.value = 0
      if (conversationList.length > 0) {
        await selectConversation(conversationList[0].id)
      }
    } finally {
      loading.value = false
    }
  }

  /** 创建新会话并切换到该会话 */
  async function createNewConversation(title?: string) {
    // 自动路由时先落到默认库，首条提问时再由后端重绑
    const kbId = activeKbId.value > 0 ? activeKbId.value : undefined
    const conversation = await createConversation({ title, kbId })
    conversations.value = [conversation, ...conversations.value]
    await selectConversation(conversation.id)
    if (activeKbId.value === 0) {
      // 保持自动路由选项，不被会话当前 kb 覆盖
      activeKbId.value = 0
    }
  }

  /** 加载指定会话详情；非自动路由时同步其绑定知识库 */
  async function selectConversation(conversationId: number) {
    currentConversation.value = await getConversationDetail(conversationId)
    const selected = conversations.value.find(item => item.id === conversationId)
    if (selected && activeKbId.value !== 0) {
      activeKbId.value = selected.kbId
    }
  }

  /**
   * 发送用户问题并走 SSE 流式回答：先本地追加用户消息，再按事件更新草稿，结束时落库并刷新列表。
   */
  async function sendQuestion(question: string) {
    if (!question.trim()) {
      return
    }
    // 无会话时先用问题前缀创建会话
    if (!currentConversation.value) {
      await createNewConversation(question.slice(0, 12))
    }

    const conversationId = currentConversation.value?.id
    if (!conversationId) {
      return
    }

    // 乐观更新：立即把用户消息插入当前会话
    const userMessage: MessageView = {
      id: Date.now(),
      role: "user",
      content: question,
      citations: [],
      createdAt: new Date().toISOString()
    }
    const conversationSnapshot = currentConversation.value
    if (!conversationSnapshot) {
      return
    }
    currentConversation.value = {
      id: conversationSnapshot.id,
      title: conversationSnapshot.title,
      kbId: conversationSnapshot.kbId,
      messages: [...conversationSnapshot.messages, userMessage]
    }

    streaming.value = true
    latestFollowUpSuggestions.value = []
    streamDraft.value = {
      messageId: null,
      content: "",
      citations: [],
      answerStatus: "streaming"
    }

    try {
      await startChatStream(
        {
          conversationId,
          kbId: activeKbId.value,
          question,
          historyRounds: 3
        },
        {
          // message_start：记录后端分配的 messageId
          onStart: payload => {
            streamDraft.value = {
              messageId: Number(payload.messageId ?? 0),
              content: "",
              citations: [],
              answerStatus: "streaming",
              intentLabel: payload.intentLabel != null ? String(payload.intentLabel) : null
            }
            // 自动路由后回写会话实际绑定的知识库
            const routedKbId = Number(payload.kbId ?? 0)
            if (routedKbId > 0 && currentConversation.value) {
              currentConversation.value = {
                ...currentConversation.value,
                kbId: routedKbId
              }
              conversations.value = conversations.value.map(item =>
                item.id === currentConversation.value?.id ? { ...item, kbId: routedKbId } : item
              )
            }
          },
          // token：增量拼接回答文本
          onToken: payload => {
            if (!streamDraft.value) return
            streamDraft.value.content += String(payload.delta ?? "")
          },
          // citation：覆盖更新引用列表
          onCitation: payload => {
            if (!streamDraft.value) return
            const items = Array.isArray(payload.items) ? payload.items : []
            streamDraft.value.citations = items.map(item => ({
              documentId: Number((item as Record<string, unknown>).documentId ?? 0),
              documentName: String((item as Record<string, unknown>).documentName ?? ""),
              chunkId: String((item as Record<string, unknown>).chunkId ?? ""),
              snippet: String((item as Record<string, unknown>).snippet ?? "")
            }))
          },
          // message_end：草稿转正为正式消息，并记录追问建议
          onEnd: payload => {
            if (!streamDraft.value || !currentConversation.value) return
            const conversationDetail = currentConversation.value
            const finalMessage: MessageView = {
              id: Number(payload.messageId ?? streamDraft.value.messageId ?? Date.now()),
              role: "assistant",
              content: streamDraft.value.content,
              citations: streamDraft.value.citations,
              answerStatus: String(payload.answerStatus ?? "success"),
              intentLabel: payload.intentLabel != null
                ? String(payload.intentLabel)
                : streamDraft.value.intentLabel,
              createdAt: new Date().toISOString()
            }
            currentConversation.value = {
              id: conversationDetail.id,
              title: conversationDetail.title,
              kbId: conversationDetail.kbId,
              messages: [...conversationDetail.messages, finalMessage]
            }
            latestFollowUpSuggestions.value = Array.isArray(payload.followUpSuggestions)
              ? payload.followUpSuggestions.map(item => String(item))
              : []
            streamDraft.value = null
            streaming.value = false
            void refreshConversationList()
          },
          // error：在草稿上展示错误文案
          onError: payload => {
            if (!streamDraft.value) return
            streamDraft.value.answerStatus = "error"
            streamDraft.value.content = String(payload.message ?? "系统繁忙，请稍后再试。")
            streaming.value = false
          }
        }
      )
    } catch (error) {
      // 网络/解析异常：用草稿展示失败信息
      streamDraft.value = {
        messageId: Date.now(),
        content: error instanceof Error ? error.message : "流式请求失败",
        citations: [],
        answerStatus: "error"
      }
      streaming.value = false
    }
  }

  /** 对指定助手消息提交点赞/踩反馈 */
  async function giveFeedback(messageId: number, rating: number, reasonCode: string, comment?: string) {
    await submitFeedback(messageId, { rating, reasonCode, comment })
  }

  /** 流式结束后静默刷新会话侧栏预览 */
  async function refreshConversationList() {
    try {
      conversations.value = await listConversations()
    } catch (error) {
      console.warn("流式响应结束后刷新会话列表失败。", error)
    }
  }

  /**
   * 切换用户时清空会话态，避免串到上一用户页面数据。
   * 由 authStore.login / logout 调用。
   */
  function resetSession() {
    knowledgeBases.value = []
    conversations.value = []
    currentConversation.value = null
    activeKbId.value = 0
    streaming.value = false
    streamDraft.value = null
    latestFollowUpSuggestions.value = []
    loading.value = false
  }

  return {
    knowledgeBases,
    conversations,
    currentConversation,
    currentMessages,
    activeKbId,
    streaming,
    streamDraft,
    latestFollowUpSuggestions,
    loading,
    bootstrap,
    createNewConversation,
    selectConversation,
    sendQuestion,
    giveFeedback,
    resetSession
  }
})
