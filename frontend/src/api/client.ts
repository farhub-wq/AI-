/**
 * 通用 HTTP 客户端：统一 API 基址、Bearer 鉴权头、业务信封解析，以及 401 清登录跳转。
 */

/** 后端统一响应信封结构 */
export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api/v1"
const ACCESS_TOKEN_KEY = "aics_access_token"
const USER_KEY = "aics_current_user"

/** 合并请求头并在存在 token 时自动附加 Authorization */
function buildHeaders(init?: RequestInit) {
  const headers = new Headers(init?.headers ?? {})
  const token = localStorage.getItem(ACCESS_TOKEN_KEY)
  if (token) {
    headers.set("Authorization", `Bearer ${token}`)
  }
  return headers
}

/** 清除本地登录态；若不在登录页则整页跳转到登录 */
export function clearAuthAndRedirect() {
  localStorage.removeItem(ACCESS_TOKEN_KEY)
  localStorage.removeItem(USER_KEY)

  // 避免在登录页重复跳转造成刷新循环
  if (window.location.pathname !== "/login") {
    window.location.href = "/login"
  }
}

/**
 * 发起 JSON API 请求：解析业务信封，校验 code/HTTP 状态，失败时抛出可读错误。
 * HTTP 401 或业务码 4010 时清登录并跳转。
 */
export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers: buildHeaders(init)
  })

  const contentType = response.headers.get("content-type") ?? ""
  if (contentType.includes("application/json")) {
    const json = (await response.json()) as ApiEnvelope<T>

    // 登录失效：清本地态并引导重新登录
    if (response.status === 401 || json.code === 4010) {
      clearAuthAndRedirect()
      throw new Error(json.message || "登录状态已失效，请重新登录。")
    }

    if (!response.ok) {
      throw new Error(json.message || `请求失败，状态码 ${response.status}`)
    }

    // 非 0 业务码统一视为失败
    if (json.code !== 0) {
      throw new Error(json.message)
    }

    return json.data
  }

  // 非 JSON 响应：仅处理错误场景
  if (!response.ok) {
    const text = await response.text()
    if (response.status === 401) {
      clearAuthAndRedirect()
      throw new Error("登录状态已失效，请重新登录。")
    }
    throw new Error(text || `请求失败，状态码 ${response.status}`)
  }

  throw new Error("响应类型异常")
}

/** 返回当前配置的 API 基址（供 multipart / SSE 等非通用请求复用） */
export function getApiBaseUrl() {
  return API_BASE_URL
}
