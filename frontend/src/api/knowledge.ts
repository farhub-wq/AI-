import { clearAuthAndRedirect, getApiBaseUrl, request } from "./client"
import type { KnowledgeBaseView, KnowledgeDocumentView } from "./types"

/**
 * 知识库 API：知识库 CRUD、文档列表/删除，以及 multipart 上传（单独处理鉴权）。
 */

/** 列出当前用户可见的全部知识库 */
export function listKnowledgeBases() {
  return request<KnowledgeBaseView[]>("/knowledge-bases")
}

/** 创建知识库（名称、类型、可选描述） */
export function createKnowledgeBase(payload: { name: string; kbType: string; description?: string }) {
  return request<KnowledgeBaseView>("/knowledge-bases", {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(payload)
  })
}

/** 列出指定知识库下的文档 */
export function listDocuments(kbId: number) {
  return request<KnowledgeDocumentView[]>(`/knowledge-bases/${kbId}/documents`)
}

/**
 * FormData 上传文档，不走通用 request（避免覆盖 Content-Type）。
 * 鉴权失败时同样清登录并跳转。
 */
export async function uploadDocument(kbId: number, file: File, priority = "general") {
  const formData = new FormData()
  formData.append("file", file)
  formData.append("priority", priority)

  const token = localStorage.getItem("aics_access_token")
  const response = await fetch(`${getApiBaseUrl()}/knowledge-bases/${kbId}/documents`, {
    method: "POST",
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    body: formData
  })
  const json = await response.json()

  // 与通用 request 一致：401 / 业务码 4010 视为登录失效
  if (response.status === 401 || json.code === 4010) {
    clearAuthAndRedirect()
    throw new Error(json.message ?? "Login state expired. Please sign in again.")
  }

  if (!response.ok || json.code !== 0) {
    throw new Error(json.message ?? "Upload failed.")
  }
  return json.data as KnowledgeDocumentView
}

/** 从知识库中删除指定文档 */
export function deleteDocument(kbId: number, documentId: number) {
  return request<void>(`/knowledge-bases/${kbId}/documents/${documentId}`, {
    method: "DELETE"
  })
}
