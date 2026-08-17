import type { LoginResponse, RegisterResponse, UserView } from "./types"
import { request } from "./client"

/**
 * 认证相关 API：
 * - 登录：常见后缀邮箱或手机号
 * - 注册：EMAIL / PHONE 分开，成功不返回令牌（需自行登录）
 * - 登出：吊销服务端 Refresh
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

/**
 * 分开注册（勿混填邮箱与手机）。
 * registerType=EMAIL 时仅传 email；=PHONE 时仅传 phone。
 * 成功返回 RegisterResponse（含 alreadyRegistered 幂等标记），不签发 token。
 * 邮箱/手机/昵称后端保证唯一；同一账号+正确密码重复提交为幂等成功。
 */
export function register(payload: {
  registerType: "EMAIL" | "PHONE"
  email?: string
  phone?: string
  password: string
  displayName: string
}) {
  return request<RegisterResponse>("/auth/register", {
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
