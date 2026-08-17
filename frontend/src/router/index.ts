import { createRouter, createWebHistory, type RouteLocationNormalized } from "vue-router"
import LoginView from "@/views/LoginView.vue"
import ChatView from "@/views/ChatView.vue"
import KnowledgeBaseView from "@/views/KnowledgeBaseView.vue"
import AdminDashboardView from "@/views/AdminDashboardView.vue"
import AgentDecomposeView from "@/views/AgentDecomposeView.vue"
import { ACCESS_TOKEN_KEY, REFRESH_TOKEN_KEY, USER_KEY } from "@/api/client"
import { isAdminUser } from "@/stores/auth"
import type { UserView } from "@/api/types"

/**
 * 前端路由表与全局登录守卫：
 * 未登录（无有效 Access/Refresh）禁止进入任何功能页，强制跳转 /login。
 * 管理后台额外要求本地缓存用户 role=ADMIN。
 */

const router = createRouter({
  history: createWebHistory(),
  routes: [
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
      meta: { requiresAuth: true, requiresAdmin: true }
    },
    {
      path: "/agent",
      name: "agent",
      component: AgentDecomposeView,
      meta: { requiresAuth: true }
    },
    {
      path: "/:pathMatch(.*)*",
      redirect: (to) => (hasValidSession() ? "/chat" : "/login")
    }
  ]
})

export function hasValidSession(): boolean {
  const access = localStorage.getItem(ACCESS_TOKEN_KEY)?.trim() ?? ""
  const refresh = localStorage.getItem(REFRESH_TOKEN_KEY)?.trim() ?? ""
  return access.length > 0 && refresh.length > 0
}

function clearBrokenSession() {
  localStorage.removeItem(ACCESS_TOKEN_KEY)
  localStorage.removeItem(REFRESH_TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

function readCachedUser(): UserView | null {
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

router.beforeEach((to: RouteLocationNormalized) => {
  const access = localStorage.getItem(ACCESS_TOKEN_KEY)?.trim() ?? ""
  const refresh = localStorage.getItem(REFRESH_TOKEN_KEY)?.trim() ?? ""
  if ((access && !refresh) || (!access && refresh)) {
    clearBrokenSession()
  }

  const needsAuth = to.matched.some((record) => record.meta.requiresAuth === true)
  const needsAdmin = to.matched.some((record) => record.meta.requiresAdmin === true)
  const isPublic = to.matched.some((record) => record.meta.public === true)

  if (needsAuth && !hasValidSession()) {
    return {
      path: "/login",
      query: to.fullPath !== "/" && to.fullPath !== "/login" ? { redirect: to.fullPath } : undefined
    }
  }

  if (needsAdmin && !isAdminUser(readCachedUser())) {
    return "/chat"
  }

  if (isPublic && hasValidSession() && to.path === "/login") {
    const redirect = typeof to.query.redirect === "string" ? to.query.redirect : "/chat"
    return redirect.startsWith("/") && !redirect.startsWith("//") ? redirect : "/chat"
  }

  if (!isPublic && !needsAuth && !hasValidSession() && to.path !== "/login") {
    return "/login"
  }

  return true
})

export default router
