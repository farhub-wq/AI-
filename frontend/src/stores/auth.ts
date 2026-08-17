import { defineStore } from "pinia"
import { ref } from "vue"
import type { LoginResponse, UserView } from "@/api/types"
import {
  getCurrentUser,
  login as loginApi,
  logout as logoutApi,
  register as registerApi
} from "@/api/auth"
import { ACCESS_TOKEN_KEY, REFRESH_TOKEN_KEY, USER_KEY } from "@/api/client"

/**
 * 认证状态：Access（短效 JWT）+ Refresh（可轮换），持久化到 localStorage。
 */

export const useAuthStore = defineStore("auth", () => {
  const accessToken = ref<string | null>(localStorage.getItem(ACCESS_TOKEN_KEY))
  const currentUser = ref<UserView | null>(readUser())
  const loading = ref(false)

  /** 登录：先清残留 token，再请求并落盘 Access/Refresh */
  async function login(account: string, password: string) {
    loading.value = true
    try {
      // 清掉旧密钥轮换前的残留 token，避免路由误判已登录
      localStorage.removeItem(ACCESS_TOKEN_KEY)
      localStorage.removeItem(REFRESH_TOKEN_KEY)
      accessToken.value = null
      const result = await loginApi(account, password)
      applyLoginResult(result)
      return result
    } finally {
      loading.value = false
    }
  }

  /** 注册成功后同样写入双令牌 */
  async function register(payload: {
    email: string
    phone?: string
    password: string
    displayName: string
  }) {
    loading.value = true
    try {
      const result = await registerApi(payload)
      applyLoginResult(result)
      return result
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

  /** 先请求后端吊销 Refresh，再清空本地态 */
  async function logout() {
    const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY)
    try {
      await logoutApi(refreshToken)
    } catch {
      // 即使吊销失败也清理本地态
    }
    accessToken.value = null
    currentUser.value = null
    localStorage.removeItem(ACCESS_TOKEN_KEY)
    localStorage.removeItem(REFRESH_TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  }

  return {
    accessToken,
    currentUser,
    loading,
    login,
    register,
    refreshCurrentUser,
    logout
  }
})

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
