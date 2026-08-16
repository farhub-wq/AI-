import { createRouter, createWebHistory } from "vue-router"
import LoginView from "../views/LoginView.vue"
import ChatView from "../views/ChatView.vue"
import KnowledgeBaseView from "../views/KnowledgeBaseView.vue"
import AdminDashboardView from "../views/AdminDashboardView.vue"
import AgentDecomposeView from "../views/AgentDecomposeView.vue"

/**
 * 前端路由表与登录守卫：未登录跳转登录页，已登录访问登录页则回对话页。
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

/** 基于本地 access token 做简单鉴权跳转 */
router.beforeEach((to) => {
  const token = localStorage.getItem("aics_access_token")
  // 访问受保护路由且无 token → 登录页
  if (to.path !== "/login" && !token) {
    return "/login"
  }
  // 已登录再进登录页 → 直接进对话
  if (to.path === "/login" && token) {
    return "/chat"
  }
  return true
})

export default router
