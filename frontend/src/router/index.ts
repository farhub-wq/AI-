import { createRouter, createWebHistory, type RouteLocationNormalized } from "vue-router"
import LoginView from "@/views/LoginView.vue"
import ChatView from "@/views/ChatView.vue"
import KnowledgeBaseView from "@/views/KnowledgeBaseView.vue"
import AdminDashboardView from "@/views/AdminDashboardView.vue"
import AgentDecomposeView from "@/views/AgentDecomposeView.vue"
import { ACCESS_TOKEN_KEY, REFRESH_TOKEN_KEY, USER_KEY } from "@/api/client"

/**
 * 前端路由表与全局登录守卫：
 * 未登录（无有效 Access/Refresh）禁止进入任何功能页，强制跳转 /login。
 */

const router = createRouter({
  history: createWebHistory(),
  routes: [
    // 根路径：已登录进对话，未登录由守卫打到登录页
    { path: "/", redirect: "/chat" },
    {
      path: "/login",
      name: "login",
      component: LoginView,
      meta: { public: true }
    },
    {
      path: "/chat",
      name: "chat",
      component: ChatView,
      meta: { requiresAuth: true }
    },
    {
      path: "/knowledge-bases",
      name: "knowledge-bases",
      component: KnowledgeBaseView,
      meta: { requiresAuth: true }
    },
    {
      path: "/admin",
      name: "admin",
      component: AdminDashboardView,
      meta: { requiresAuth: true }
    },
    {
      path: "/agent",
      name: "agent",
      component: AgentDecomposeView,
      meta: { requiresAuth: true }
    },
    // 未知路径：未登录去登录，已登录回对话
    {
      path: "/:pathMatch(.*)*",
      redirect: (to) => (hasValidSession() ? "/chat" : "/login")
    }
  ]
})

/**
 * 是否具备可进入功能页的会话：
 * Access + Refresh 均非空才视为已登录（仅有残缺 token 一律清掉）。
 */
export function hasValidSession(): boolean {
  const access = localStorage.getItem(ACCESS_TOKEN_KEY)?.trim() ?? ""
  const refresh = localStorage.getItem(REFRESH_TOKEN_KEY)?.trim() ?? ""
  return access.length > 0 && refresh.length > 0
}

/** 清空残缺/无效本地登录态，避免误判已登录。 */
function clearBrokenSession() {
  localStorage.removeItem(ACCESS_TOKEN_KEY)
  localStorage.removeItem(REFRESH_TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

/**
 * 全局前置守卫：
 * 1. 需要登录的页面：无有效会话 → 强制 /login（可带 redirect 方便登后回跳）
 * 2. 登录页：已有有效会话 → 进 /chat；仅有残缺 token → 清理后留在登录页
 */
router.beforeEach((to: RouteLocationNormalized) => {
  // 残缺会话（有 Access 无 Refresh 或反之）：一律清掉，视为未登录
  const access = localStorage.getItem(ACCESS_TOKEN_KEY)?.trim() ?? ""
  const refresh = localStorage.getItem(REFRESH_TOKEN_KEY)?.trim() ?? ""
  if ((access && !refresh) || (!access && refresh)) {
    clearBrokenSession()
  }

  const needsAuth = to.matched.some((record) => record.meta.requiresAuth === true)
  const isPublic = to.matched.some((record) => record.meta.public === true)

  if (needsAuth && !hasValidSession()) {
    return {
      path: "/login",
      query: to.fullPath !== "/" && to.fullPath !== "/login" ? { redirect: to.fullPath } : undefined
    }
  }

  if (isPublic && hasValidSession() && to.path === "/login") {
    // 已登录访问登录页：进入业务首页，避免停留在登录 UI
    const redirect = typeof to.query.redirect === "string" ? to.query.redirect : "/chat"
    return redirect.startsWith("/") && !redirect.startsWith("//") ? redirect : "/chat"
  }

  // 非 public 且未标 requiresAuth 的路由：默认也要求登录（防御性）
  if (!isPublic && !needsAuth && !hasValidSession() && to.path !== "/login") {
    return "/login"
  }

  return true
})

export default router
