<script setup lang="ts">
import type { ConversationSummaryView } from "@/api/types"

/**
 * 历史会话侧栏：列出会话摘要，支持新建与切换当前会话。
 */

defineProps<{
  conversations: ConversationSummaryView[]
  activeConversationId?: number
}>()

const emit = defineEmits<{
  (e: "create"): void
  (e: "select", conversationId: number): void
}>()
</script>

<template>
  <div class="sidebar-wrap glass-panel">
    <div class="sidebar-header">
      <div>
        <h2 class="section-title">历史会话</h2>
        <p class="section-subtitle">按最近更新排序，方便回看近期对话。</p>
      </div>
      <el-button type="primary" @click="emit('create')">新建会话</el-button>
    </div>

    <div class="conversation-list">
      <button
        v-for="conversation in conversations"
        :key="conversation.id"
        class="conversation-item"
        :class="{ active: conversation.id === activeConversationId }"
        @click="emit('select', conversation.id)"
      >
        <strong>{{ conversation.title }}</strong>
        <span>{{ conversation.lastMessagePreview || "暂无消息" }}</span>
        <small>{{ new Date(conversation.updatedAt).toLocaleString() }}</small>
      </button>
    </div>
  </div>
</template>

<style scoped>
.sidebar-wrap {
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.conversation-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 620px;
  overflow: auto;
}

.conversation-item {
  padding: 14px;
  border: 1px solid rgba(23, 32, 51, 0.08);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.72);
  display: flex;
  flex-direction: column;
  gap: 6px;
  cursor: pointer;
  text-align: left;
}

.conversation-item.active {
  border-color: rgba(255, 170, 70, 0.6);
  background: linear-gradient(135deg, rgba(255, 239, 219, 0.9), rgba(255, 255, 255, 0.92));
}

.conversation-item span,
.conversation-item small {
  color: #63728e;
}
</style>
