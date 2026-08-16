import { defineStore } from "pinia"
import { ref } from "vue"
import type { LoginResponse, UserView } from "@/api/types"
import { getCurrentUser, login as loginApi, register as registerApi } from "@/api/auth"

/**
 * 认证状态：维护 accessToken / 当前用户，登录注册后持久化到 localStorage。
 */

const ACCESS_TOKEN_KEY = "aics_access_token"
const USER_KEY = "aics_current_user"

export const useAuthStore = defineStore("auth", () => {
  const accessToken = ref<string | null>(localStorage.getItem(ACCESS_TOKEN_KEY))
  const currentUser = ref<UserView | null>(readUser())
  const loading = ref(false)

  /** 调用登录 API，成功后写入 token 与用户信息 */
  async function login(account: string, password: string) {
    loading.value = true
    try {
      const result = await loginApi(account, password)
      applyLoginResult(result)
      return result
    } finally {
      loading.value = false
    }
  }

  /** 调用注册 API，成功后同样持久化登录态 */
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

  /** 有 token 时向后端刷新当前用户资料并写回本地 */
  async function refreshCurrentUser() {
    // 无 token 时跳过，避免无效请求
    if (!accessToken.value) {
      return null
    }
    const user = await getCurrentUser()
    currentUser.value = user
    localStorage.setItem(USER_KEY, JSON.stringify(user))
    return user
  }

  /** 写入 token 与用户信息到内存和本地存储 */
  function applyLoginResult(result: LoginResponse) {
    accessToken.value = result.accessToken
    currentUser.value = result.user
    localStorage.setItem(ACCESS_TOKEN_KEY, result.accessToken)
    localStorage.setItem(USER_KEY, JSON.stringify(result.user))
  }

  /** 清空内存与本地登录态 */
  function logout() {
    accessToken.value = null
    currentUser.value = null
    localStorage.removeItem(ACCESS_TOKEN_KEY)
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
    // 损坏数据视为未登录
    return null
  }
}
