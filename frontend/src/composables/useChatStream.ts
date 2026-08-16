import { clearAuthAndRedirect, getApiBaseUrl } from "@/api/client"

/**
 * SSE 流式问答：POST /chat/stream，按 event 分发 start/token/citation/end/error。
 */

/** 流式事件回调集合，由 chat store 注入以更新草稿与终态 */
export interface StreamHandlers {
  onStart: (payload: Record<string, unknown>) => void
  onToken: (payload: Record<string, unknown>) => void
  onCitation: (payload: Record<string, unknown>) => void
  onEnd: (payload: Record<string, unknown>) => void
  onError: (payload: Record<string, unknown>) => void
}

/**
 * 发起 SSE 流式问答请求：读取响应体，解析 event/data 帧并回调 handlers。
 * 401 时清登录跳转；message_end 后连接关闭视为正常结束。
 */
export async function startChatStream(
  payload: {
    conversationId?: number
    kbId?: number
    question: string
    historyRounds: number
  },
  handlers: StreamHandlers
) {
  const token = localStorage.getItem("aics_access_token")
  let streamEnded = false
  const response = await fetch(`${getApiBaseUrl()}/chat/stream`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify(payload)
  })

  if (!response.ok) {
    const text = await response.text()
    // 流式接口鉴权失败：解析业务码后清登录
    if (response.status === 401) {
      try {
        const payloadData = JSON.parse(text) as { code?: number; message?: string }
        if (payloadData.code === 4010) {
          clearAuthAndRedirect()
          throw new Error(payloadData.message ?? "Login state expired. Please sign in again.")
        }
      } catch {
        clearAuthAndRedirect()
        throw new Error("Login state expired. Please sign in again.")
      }
    }
    throw new Error(text || "Streaming request failed")
  }

  const reader = response.body?.getReader()
  const decoder = new TextDecoder("utf-8")
  let buffer = ""

  if (!reader) {
    throw new Error("Streaming is not supported in the current environment")
  }

  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) {
        break
      }

      // 按 SSE 空行切帧，残留半包留在 buffer
      buffer += decoder.decode(value, { stream: true })
      const parts = buffer.split("\n\n")
      buffer = parts.pop() ?? ""

      for (const part of parts) {
        const lines = part.split("\n")
        const event = lines.find(line => line.startsWith("event:"))?.replace("event:", "").trim()
        const dataLine = lines.find(line => line.startsWith("data:"))?.replace("data:", "").trim()
        if (!event || !dataLine) {
          continue
        }
        const payloadData = JSON.parse(dataLine) as Record<string, unknown>

        // 按事件类型分发给上层 handlers
        if (event === "message_start") handlers.onStart(payloadData)
        if (event === "token") handlers.onToken(payloadData)
        if (event === "citation") handlers.onCitation(payloadData)
        if (event === "message_end") {
          streamEnded = true
          handlers.onEnd(payloadData)
        }
        if (event === "error") handlers.onError(payloadData)
      }
    }
  } catch (error) {
    // message_end 后连接关闭可能抛错，视为正常结束
    if (streamEnded) {
      return
    }
    throw error
  }
}
