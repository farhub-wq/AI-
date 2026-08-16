<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue"
import { ElMessage } from "element-plus"
import ConversationSidebar from "@/components/ConversationSidebar.vue"
import MessageList from "@/components/MessageList.vue"
import CitationPanel from "@/components/CitationPanel.vue"
import FeedbackBar from "@/components/FeedbackBar.vue"
import { useChatStore } from "@/stores/chat"

/**
 * 智能对话页：会话侧栏、消息区、引用面板与反馈栏，驱动 SSE 流式问答。
 */

const chatStore = useChatStore()
const question = ref("")

/** 取最近一条助手消息，用于引用与反馈绑定 */
const currentAssistantMessage = computed(() => {
  const messages = chatStore.currentMessages
  return [...messages].reverse().find(message => message.role === "assistant") ?? null
})

const citations = computed(() => currentAssistantMessage.value?.citations ?? [])

/** 流式输出中禁止提交反馈，避免评分落到未完成回答 */
const feedbackDisabled = computed(() => {
  const message = currentAssistantMessage.value
  return !message || message.answerStatus === "streaming"
})

onMounted(() => {
  // 首次进入且尚无会话数据时再 bootstrap
  if (chatStore.conversations.length === 0) {
    chatStore.bootstrap().catch(handleError)
  }
})

// 手动指定知识库时，切换会话才同步其绑定库；自动路由(0)保持不变
watch(() => chatStore.currentConversation?.kbId, (kbId) => {
  if (kbId && chatStore.activeKbId !== 0) {
    chatStore.activeKbId = kbId
  }
})

/** 发送输入框中的问题并清空草稿 */
async function sendQuestion() {
  if (!question.value.trim()) {
    return
  }
  try {
    await chatStore.sendQuestion(question.value)
    question.value = ""
  } catch (error) {
    handleError(error)
  }
}

/** 点击追问建议：填入问题并立即发送 */
async function sendFollowUp(text: string) {
  question.value = text
  await sendQuestion()
}

/** 新建空白会话 */
async function createConversation() {
  try {
    await chatStore.createNewConversation()
  } catch (error) {
    handleError(error)
  }
}

/** 对当前助手消息提交反馈 */
async function submitFeedback(payload: { rating: number; reasonCode: string; comment: string }) {
  if (!currentAssistantMessage.value) {
    return
  }
  try {
    await chatStore.giveFeedback(currentAssistantMessage.value.id, payload.rating, payload.reasonCode, payload.comment)
    ElMessage.success("反馈已提交")
  } catch (error) {
    handleError(error)
  }
}

/** 统一错误提示 */
function handleError(error: unknown) {
  ElMessage.error(error instanceof Error ? error.message : "请求失败")
}

/** 知识库类型码转中文标签 */
function kbTypeLabel(kbType: string) {
  if (kbType === "customer_support") return "客服知识库"
  if (kbType === "technical_docs") return "技术文档库"
  return kbType
}
</script>

<template>
  <div class="chat-page">
    <div class="hero-banner glass-panel">
      <div>
        <span class="badge-soft">客服问答链路</span>
      </div>
      <el-select v-model="chatStore.activeKbId" style="width: 280px">
        <el-option label="自动路由（按问题选最相关知识库）" :value="0" />
        <el-option
          v-for="kb in chatStore.knowledgeBases"
          :key="kb.id"
          :label="`${kb.name}（${kbTypeLabel(kb.kbType)}）`"
          :value="kb.id"
        />
      </el-select>
    </div>

    <div class="chat-layout">
      <ConversationSidebar
        :conversations="chatStore.conversations"
        :active-conversation-id="chatStore.currentConversation?.id"
        @create="createConversation"
        @select="chatStore.selectConversation"
      />

      <div class="chat-main">
        <MessageList :messages="chatStore.currentMessages" />

        <div v-if="chatStore.latestFollowUpSuggestions.length > 0" class="follow-up-row">
          <el-button
            v-for="item in chatStore.latestFollowUpSuggestions"
            :key="item"
            round
            @click="sendFollowUp(item)"
          >
            {{ item }}
          </el-button>
        </div>

        <div class="composer glass-panel">
          <el-input
            v-model="question"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="请输入问题，例如：退货有时间限制吗？"
          />
          <div class="composer-actions">
            <span class="section-subtitle">可试：退货有时间限制吗？ / 订单多久发货？</span>
            <el-button type="primary" :loading="chatStore.streaming" @click="sendQuestion">发送</el-button>
          </div>
        </div>
      </div>

      <div class="chat-side">
        <CitationPanel :citations="citations" />
        <FeedbackBar :disabled="feedbackDisabled" @submit="submitFeedback" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.hero-banner {
  padding: 20px 22px;
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
}

.chat-layout {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr) 340px;
  gap: 18px;
}

.chat-main,
.chat-side {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.follow-up-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.composer {
  padding: 18px;
}

.composer-actions {
  margin-top: 12px;
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
}

@media (max-width: 1280px) {
  .chat-layout {
    grid-template-columns: 1fr;
  }
}
</style>
