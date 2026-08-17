import { createRouter, createWebHistory } from "vue-router"
import LoginView from "@/views/LoginView.vue"
import ChatView from "@/views/ChatView.vue"
import KnowledgeBaseView from "@/views/KnowledgeBaseView.vue"
import AdminDashboardView from "@/views/AdminDashboardView.vue"
import AgentDecomposeView from "@/views/AgentDecomposeView.vue"
import { ACCESS_TOKEN_KEY, REFRESH_TOKEN_KEY } from "@/api/client"

/**
 * 前端路由表与登录守卫（配合短效 Access + Refresh）。
 */

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", redirect: "/chat" },
    { path: "/login", component: LoginView },
    { path: "/chat", component: ChatView },
    { path: "/knowledge-bases", component: KnowledgeBaseView },
    { path: "/admin", component: AdminDashboardView },
    { path: "/agent", component: AgentDecomposeView }
  ]
})

/**
 * 鉴权跳转：
 * - 无 Access → 强制登录页
 * - 进登录页但仅有废 Access、无 Refresh → 清 Access 并允许留在登录页
 * - 双令牌齐全再进登录页 → 视为已登录，跳对话页
 */
router.beforeEach((to) => {
  const token = localStorage.getItem(ACCESS_TOKEN_KEY)
  if (to.path !== "/login" && !token) {
    return "/login"
  }
  if (to.path === "/login" && token) {
    const refresh = localStorage.getItem(REFRESH_TOKEN_KEY)
    if (!refresh) {
      localStorage.removeItem(ACCESS_TOKEN_KEY)
      return true
    }
    return "/chat"
  }
  return true
})

export default router
