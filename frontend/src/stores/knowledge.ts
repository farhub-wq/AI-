import { defineStore } from "pinia"
import { ref } from "vue"
import { createKnowledgeBase, deleteDocument, listDocuments, listKnowledgeBases, uploadDocument } from "@/api/knowledge"
import type { KnowledgeBaseView, KnowledgeDocumentView } from "@/api/types"

/**
 * 知识库状态：知识库列表、当前选中库及其文档的加载/创建/上传/删除。
 * 对 processing 状态文档自动短轮询，直到就绪或失败。
 */

export const useKnowledgeStore = defineStore("knowledge", () => {
  const knowledgeBases = ref<KnowledgeBaseView[]>([])
  const selectedKbId = ref<number | null>(null)
  const documents = ref<KnowledgeDocumentView[]>([])
  const loading = ref(false)
  let pollTimer: ReturnType<typeof setInterval> | null = null

  /** 停止文档状态轮询 */
  function stopPolling() {
    if (pollTimer != null) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  /** 若存在 processing 文档则每 1.5s 轮询，对齐后端异步向量化状态流转；请求失败立即停，避免坏会话刷屏 */
  function startPollingIfNeeded() {
    stopPolling()
    if (!documents.value.some(item => item.status === "processing")) {
      return
    }
    pollTimer = setInterval(async () => {
      if (selectedKbId.value == null) {
        stopPolling()
        return
      }
      try {
        documents.value = await listDocuments(selectedKbId.value)
        if (!documents.value.some(item => item.status === "processing")) {
          stopPolling()
        }
      } catch {
        stopPolling()
      }
    }, 1500)
  }

  /** 进入页面：加载知识库列表，并默认选中第一个及其文档 */
  async function bootstrap() {
    loading.value = true
    try {
      knowledgeBases.value = await listKnowledgeBases()
      if (knowledgeBases.value.length > 0 && selectedKbId.value == null) {
        selectedKbId.value = knowledgeBases.value[0].id
      }
      if (selectedKbId.value != null) {
        documents.value = await listDocuments(selectedKbId.value)
        startPollingIfNeeded()
      }
    } finally {
      loading.value = false
    }
  }

  /** 切换当前知识库并刷新文档列表 */
  async function selectKnowledgeBase(kbId: number) {
    selectedKbId.value = kbId
    documents.value = await listDocuments(kbId)
    startPollingIfNeeded()
  }

  /** 创建知识库并切换到新建库（文档列表先置空） */
  async function addKnowledgeBase(payload: { name: string; kbType: string; description?: string }) {
    const knowledgeBase = await createKnowledgeBase(payload)
    knowledgeBases.value = [knowledgeBase, ...knowledgeBases.value]
    selectedKbId.value = knowledgeBase.id
    documents.value = []
    stopPolling()
  }

  /** 向当前知识库上传文档；返回后多为 processing，启动轮询 */
  async function addDocument(file: File, priority = "general") {
    if (selectedKbId.value == null) {
      return
    }
    const document = await uploadDocument(selectedKbId.value, file, priority)
    documents.value = [document, ...documents.value.filter(item => item.id !== document.id)]
    startPollingIfNeeded()
  }

  /** 删除当前知识库下的指定文档并同步本地列表 */
  async function removeDocument(documentId: number) {
    if (selectedKbId.value == null) {
      return
    }
    await deleteDocument(selectedKbId.value, documentId)
    documents.value = documents.value.filter(item => item.id !== documentId)
    startPollingIfNeeded()
  }

  /**
   * 切换用户时清空知识库页面缓存并停止轮询。
   * 由 authStore.login / logout 调用。
   */
  function resetSession() {
    stopPolling()
    knowledgeBases.value = []
    selectedKbId.value = null
    documents.value = []
    loading.value = false
  }

  return {
    knowledgeBases,
    selectedKbId,
    documents,
    loading,
    bootstrap,
    selectKnowledgeBase,
    addKnowledgeBase,
    addDocument,
    removeDocument,
    resetSession
  }
})
