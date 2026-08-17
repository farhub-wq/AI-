import type { LoginResponse, UserView } from "./types"
import { request } from "./client"

/**
 * 认证相关 API：登录、注册、当前用户、登出吊销 Refresh。
 */

/** 使用邮箱/手机号与密码登录，返回 Access + Refresh */
export function login(account: string, password: string) {
  return request<LoginResponse>("/auth/login", {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ account, password })
  })
}

/** 注册新账号，成功后同样返回双令牌 */
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

/** 根据当前 Bearer Access 获取登录用户资料 */
export function getCurrentUser() {
  return request<UserView>("/auth/me")
}

/** 登出并吊销服务端 Refresh Token */
export function logout(refreshToken: string | null) {
  return request<null>("/auth/logout", {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ refreshToken: refreshToken ?? "" })
  })
}
