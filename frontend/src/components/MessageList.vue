<script setup lang="ts">
import { nextTick, ref, watch } from "vue"
import type { MessageView } from "@/api/types"

/**
 * 消息列表：按角色渲染用户/助手气泡，并展示意图标签与回答状态文案。
 * 新消息或流式增量到来时自动滚到最底部，避免用户看不到最新回答。
 */

const props = defineProps<{
  messages: MessageView[]
}>()

/** 可滚动容器 */
const listRef = ref<HTMLElement | null>(null)
/** 列表底部哨兵，用于 scrollIntoView */
const bottomRef = ref<HTMLElement | null>(null)

/**
 * 滚到最新消息底部。
 * @param smooth 是否平滑滚动（首屏/切换会话用瞬时，流式输出用平滑）
 */
async function scrollToLatest(smooth = true) {
  await nextTick()
  const container = listRef.value
  const bottom = bottomRef.value
  if (!container) {
    return
  }
  if (bottom) {
    bottom.scrollIntoView({ behavior: smooth ? "smooth" : "auto", block: "end" })
  }
  // 再强制一次 scrollTop，兼容 scrollIntoView 被外层布局打断的情况
  container.scrollTop = container.scrollHeight
}

/** 将 answerStatus 转为可读中文状态说明 */
function statusText(message: MessageView) {
  if (!message.answerStatus) return ""
  if (message.answerStatus === "fallback") return "知识库兜底回答"
  if (message.answerStatus === "degraded") return "模型不可用，已回退为证据摘要"
  if (message.answerStatus === "error") return "处理失败"
  if (message.answerStatus === "streaming") return "正在流式输出"
  return "已完成"
}

/** 消息角色码转中文标签 */
function roleLabel(role: string) {
  return role === "user" ? "用户" : "AI 助手"
}

/** 意图标签样式分类，便于会话中一眼识别 */
function intentClass(label?: string | null) {
  if (!label) return ""
  if (label.includes("售后")) return "intent after-sales"
  if (label.includes("投诉")) return "intent complaint"
  if (label.includes("闲聊")) return "intent chitchat"
  return "intent product"
}

/** 条数变化（发问/切会话）：瞬时滚到底 */
watch(
  () => props.messages.length,
  () => {
    void scrollToLatest(false)
  }
)

/**
 * 内容变化（流式 token 追加、状态从 streaming→完成）：持续跟到底。
 * 用最后一条内容长度 + 状态做依赖，避免 deep watch 过重。
 */
watch(
  () => {
    const last = props.messages[props.messages.length - 1]
    if (!last) {
      return ""
    }
    return `${last.id}:${last.content.length}:${last.answerStatus ?? ""}`
  },
  () => {
    void scrollToLatest(true)
  }
)
</script>

<template>
  <div ref="listRef" class="message-list glass-panel">
    <div
      v-for="message in messages"
      :key="message.id"
      class="message-card"
      :class="message.role"
    >
      <div class="message-meta">
        <span class="badge-soft">{{ roleLabel(message.role) }}</span>
        <small v-if="message.intentLabel" :class="intentClass(message.intentLabel)">
          意图：{{ message.intentLabel }}
        </small>
        <small>{{ new Date(message.createdAt).toLocaleTimeString() }}</small>
      </div>
      <p>{{ message.content }}</p>
      <div v-if="message.answerStatus" class="message-status mono">{{ statusText(message) }}</div>
    </div>
    <!-- 底部锚点：保证最新回答始终进入可视区 -->
    <div ref="bottomRef" class="scroll-anchor" aria-hidden="true" />
  </div>
</template>

<style scoped>
.message-list {
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 360px;
  max-height: 52vh;
  overflow: auto;
  scroll-behavior: smooth;
}

.scroll-anchor {
  width: 100%;
  height: 1px;
  flex-shrink: 0;
}

.message-card {
  padding: 12px 14px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.55);
}

.message-card.user {
  align-self: flex-end;
  background: rgba(37, 99, 235, 0.08);
}

.message-card.assistant {
  align-self: flex-start;
}

.message-meta {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.intent {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.06);
}

.intent.after-sales {
  background: rgba(245, 158, 11, 0.18);
  color: #92400e;
}

.intent.complaint {
  background: rgba(239, 68, 68, 0.16);
  color: #991b1b;
}

.intent.chitchat {
  background: rgba(100, 116, 139, 0.16);
  color: #334155;
}

.intent.product {
  background: rgba(37, 99, 235, 0.14);
  color: #1d4ed8;
}

.message-status {
  margin-top: 8px;
  font-size: 12px;
  color: #b45309;
}
</style>
