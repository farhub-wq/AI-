/**
 * 通用 HTTP 客户端：统一 API 基址、Bearer 鉴权；Access 过期时用 Refresh 静默续期一次。
 */

/** 后端统一响应信封结构 */
export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api/v1"

/** localStorage：短效 Access JWT */
export const ACCESS_TOKEN_KEY = "aics_access_token"
/** localStorage：不透明 Refresh（可轮换） */
export const REFRESH_TOKEN_KEY = "aics_refresh_token"
/** localStorage：当前用户缓存 */
export const USER_KEY = "aics_current_user"

/** 并发 401 时合并为单次 refresh，避免重复轮换 */
let refreshInFlight: Promise<boolean> | null = null

/** 合并请求头；存在 Access 时自动附加 Authorization */
function buildHeaders(init?: RequestInit) {
  const headers = new Headers(init?.headers ?? {})
  const token = localStorage.getItem(ACCESS_TOKEN_KEY)
  if (token) {
    headers.set("Authorization", `Bearer ${token}`)
  }
  return headers
}

/** 清除本地登录态；若不在登录页则整页跳转登录 */
export function clearAuthAndRedirect() {
  localStorage.removeItem(ACCESS_TOKEN_KEY)
  localStorage.removeItem(REFRESH_TOKEN_KEY)
  localStorage.removeItem(USER_KEY)

  if (window.location.pathname !== "/login") {
    window.location.href = "/login"
  }
}

/**
 * 调用 /auth/refresh 轮换令牌对。
 * @returns 是否刷新成功
 */
async function tryRefreshAccessToken(): Promise<boolean> {
  const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY)
  if (!refreshToken) {
    return false
  }
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      try {
        const response = await fetch(`${API_BASE_URL}/auth/refresh`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ refreshToken })
        })
        if (!response.ok) {
          return false
        }
        const json = (await response.json()) as ApiEnvelope<{
          accessToken: string
          refreshToken: string
          user: unknown
        }>
        if (json.code !== 0 || !json.data?.accessToken) {
          return false
        }
        // 轮换后同时更新 Access 与新的 Refresh
        localStorage.setItem(ACCESS_TOKEN_KEY, json.data.accessToken)
        localStorage.setItem(REFRESH_TOKEN_KEY, json.data.refreshToken)
        if (json.data.user) {
          localStorage.setItem(USER_KEY, JSON.stringify(json.data.user))
        }
        return true
      } catch {
        return false
      } finally {
        refreshInFlight = null
      }
    })()
  }
  return refreshInFlight
}

/**
 * 发起 JSON API 请求：解析业务信封；业务接口遇 401 时先尝试 Refresh 再重试一次。
 * 认证相关路径（/auth/*）不自动 refresh，避免循环。
 */
export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const doFetch = () =>
    fetch(`${API_BASE_URL}${path}`, {
      ...init,
      headers: buildHeaders(init)
    })

  let response = await doFetch()
  const unauthorized = response.status === 401
  if (unauthorized && !path.startsWith("/auth/")) {
    const refreshed = await tryRefreshAccessToken()
    if (refreshed) {
      response = await doFetch()
    }
  }

  const contentType = response.headers.get("content-type") ?? ""
  if (contentType.includes("application/json")) {
    const json = (await response.json()) as ApiEnvelope<T>

    if (response.status === 401 || json.code === 4010) {
      clearAuthAndRedirect()
      throw new Error(json.message || "登录状态已失效，请重新登录。")
    }

    if (!response.ok) {
      throw new Error(json.message || `请求失败，状态码 ${response.status}`)
    }

    if (json.code !== 0) {
      throw new Error(json.message)
    }

    return json.data
  }

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

/** 返回当前配置的 API 基址（供 multipart / SSE 等复用） */
export function getApiBaseUrl() {
  return API_BASE_URL
}
