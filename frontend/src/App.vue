<script setup lang="ts">
import { computed } from "vue"
import { useRoute, useRouter } from "vue-router"
import { useAuthStore } from "@/stores/auth"

/**
 * 应用根布局：登录页全屏展示；其余路由使用侧栏壳（导航、用户信息、退出）。
 */

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const menuItems = [
  { label: "智能对话", path: "/chat" },
  { label: "知识库管理", path: "/knowledge-bases" },
  { label: "管理后台", path: "/admin" },
  { label: "需求拆解 Agent", path: "/agent" }
]

/** 登录页不展示侧栏壳，其它页面展示 */
const showShell = computed(() => route.path !== "/login")

/** 侧栏导航跳转 */
function go(path: string) {
  router.push(path)
}

/** 退出登录并回到登录页 */
function logout() {
  authStore.logout()
  router.push("/login")
}
</script>

<template>
  <router-view v-if="!showShell" />

  <div v-else class="page-shell">
    <div class="app-shell glass-panel">
      <aside class="shell-sidebar">
        <div class="brand-block">
          <div class="brand-kicker">智惠客服</div>
          <h1>AI 智能客服控制台</h1>
        </div>

        <nav class="shell-nav">
          <button
            v-for="item in menuItems"
            :key="item.path"
            class="nav-item"
            :class="{ active: route.path.startsWith(item.path) }"
            @click="go(item.path)"
          >
            {{ item.label }}
          </button>
        </nav>

        <div class="shell-footer">
          <div class="user-block">
            <span class="badge-soft">当前账号</span>
            <strong>{{ authStore.currentUser?.displayName ?? "未登录" }}</strong>
            <span>{{ authStore.currentUser?.email ?? "demo@example.com" }}</span>
          </div>
          <el-button type="primary" plain @click="logout">退出登录</el-button>
        </div>
      </aside>

      <main class="shell-main">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
.app-shell {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  min-height: calc(100vh - 48px);
  overflow: hidden;
}

.shell-sidebar {
  display: flex;
  flex-direction: column;
  gap: 24px;
  padding: 28px 22px;
  border-right: 1px solid rgba(23, 32, 51, 0.08);
  background:
    linear-gradient(180deg, rgba(255, 248, 239, 0.92), rgba(255, 255, 255, 0.6));
}

.brand-block h1 {
  margin: 8px 0 0;
  font-size: 28px;
  line-height: 1.1;
}

.brand-block p {
  margin: 8px 0 0;
  color: #62718b;
  font-size: 14px;
}

.brand-kicker {
  font-size: 12px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: #b45a00;
  font-weight: 700;
}

.shell-nav {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.nav-item {
  border: 0;
  text-align: left;
  border-radius: 16px;
  padding: 14px 16px;
  font-size: 15px;
  color: #32415e;
  background: rgba(255, 255, 255, 0.62);
  cursor: pointer;
  transition: transform 160ms ease, background 160ms ease, color 160ms ease;
}

.nav-item:hover,
.nav-item.active {
  transform: translateX(4px);
  color: #172033;
  background: linear-gradient(90deg, rgba(255, 189, 110, 0.24), rgba(255, 255, 255, 0.9));
}

.shell-footer {
  margin-top: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.user-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 14px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.75);
}

.user-block span:last-child {
  color: #61718d;
  font-size: 13px;
}

.shell-main {
  min-width: 0;
  padding: 24px;
}

@media (max-width: 960px) {
  .app-shell {
    grid-template-columns: 1fr;
  }

  .shell-sidebar {
    border-right: 0;
    border-bottom: 1px solid rgba(23, 32, 51, 0.08);
  }
}
</style>
