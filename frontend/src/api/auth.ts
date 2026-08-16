import type { LoginResponse, UserView } from "./types"
import { request } from "./client"

/**
 * 认证相关 API 封装：登录、注册、拉取当前用户。
 */

/** 使用邮箱/手机号与密码登录，返回令牌与用户信息 */
export function login(account: string, password: string) {
  return request<LoginResponse>("/auth/login", {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ account, password })
  })
}

/** 注册新账号，成功后同样返回登录令牌 */
export function register(payload: {
  email: string
  phone?: string
  password: string
  displayName: string
}) {
  return request<LoginResponse>("/auth/register", {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(payload)
  })
}

/** 根据当前 Bearer 令牌获取登录用户资料 */
export function getCurrentUser() {
  return request<UserView>("/auth/me")
}
