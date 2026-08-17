import { defineStore } from "pinia"
import { computed, ref } from "vue"
import type { LoginResponse, RegisterResponse, UserView } from "@/api/types"
import {
  getCurrentUser,
  login as loginApi,
  logout as logoutApi,
  register as registerApi
} from "@/api/auth"
import { ACCESS_TOKEN_KEY, REFRESH_TOKEN_KEY, USER_KEY } from "@/api/client"
import { useChatStore } from "@/stores/chat"
import { useAgentStore } from "@/stores/agent"
import { useAdminStore } from "@/stores/admin"
import { useKnowledgeStore } from "@/stores/knowledge"

/**
 * 认证状态：Access（短效 JWT）+ Refresh（可轮换），持久化到 localStorage。
 * 登录/登出时清空各业务 Store，避免不同用户看到上一用户的前端缓存页面数据。
 */

export const useAuthStore = defineStore("auth", () => {
  const accessToken = ref<string | null>(localStorage.getItem(ACCESS_TOKEN_KEY))
  const currentUser = ref<UserView | null>(readUser())
  const loading = ref(false)
  const isAdmin = computed(() => isAdminUser(currentUser.value))

  /**
   * 登录：先清残留 token 与业务态，再请求并落盘 Access/Refresh。
   * 保证切换账号后不会残留上一用户的会话/规划等前端状态。
   */
  async function login(account: string, password: string) {
    loading.value = true
    try {
      clearSessionCaches()
      localStorage.removeItem(ACCESS_TOKEN_KEY)
      localStorage.removeItem(REFRESH_TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
      accessToken.value = null
      currentUser.value = null
      const result = await loginApi(account, password)
      applyLoginResult(result)
      return result
    } finally {
      loading.value = false
    }
  }

  /**
   * 注册：仅调用接口拿提示文案，绝不写入令牌、绝不视为已登录。
   * 后端保证邮箱/手机/昵称唯一；重复提交同一账号+正确密码为幂等成功。
   * 前端应提示「已注册，请返回登录页登录」。
   */
  async function register(payload: {
    registerType: "EMAIL" | "PHONE"
    email?: string
    phone?: string
    password: string
    displayName: string
  }): Promise<RegisterResponse> {
    loading.value = true
    try {
      return await registerApi(payload)
    } finally {
      loading.value = false
    }
  }

  /** 有 Access 时向后端刷新当前用户资料 */
  async function refreshCurrentUser() {
    if (!accessToken.value) {
      return null
    }
    const user = await getCurrentUser()
    currentUser.value = user
    localStorage.setItem(USER_KEY, JSON.stringify(user))
    return user
  }

  /** 写入 Access、Refresh 与用户信息到内存和本地存储 */
  function applyLoginResult(result: LoginResponse) {
    accessToken.value = result.accessToken
    currentUser.value = result.user
    localStorage.setItem(ACCESS_TOKEN_KEY, result.accessToken)
    localStorage.setItem(REFRESH_TOKEN_KEY, result.refreshToken)
    localStorage.setItem(USER_KEY, JSON.stringify(result.user))
  }

  /** 先请求后端吊销 Refresh，再清空本地态与业务缓存 */
  async function logout() {
    const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY)
    try {
      await logoutApi(refreshToken)
    } catch {
      // 即使吊销失败也清理本地态，避免残留登录态
    }
    accessToken.value = null
    currentUser.value = null
    localStorage.removeItem(ACCESS_TOKEN_KEY)
    localStorage.removeItem(REFRESH_TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
    clearSessionCaches()
  }

  return {
    accessToken,
    currentUser,
    loading,
    isAdmin,
    login,
    register,
    refreshCurrentUser,
    logout
  }
})

/** 是否管理员（管理后台入口与路由守卫用）。 */
export function isAdminUser(user: UserView | null | undefined): boolean {
  return (user?.role ?? "").toUpperCase() === "ADMIN"
}

/**
 * 清空对话 / Agent / 看板 / 知识库前端缓存。
 * 防止用户 A 登出后用户 B 仍短暂看到 A 的页面数据。
 */
function clearSessionCaches() {
  useChatStore().resetSession()
  useAgentStore().resetSession()
  useAdminStore().resetSession()
  useKnowledgeStore().resetSession()
}

/** 从 localStorage 安全解析已缓存的用户对象 */
function readUser(): UserView | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) {
    return null
  }
  try {
    return JSON.parse(raw) as UserView
  } catch {
    return null
  }
}
