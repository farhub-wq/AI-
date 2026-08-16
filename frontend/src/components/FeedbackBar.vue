<script setup lang="ts">
import { ref } from "vue"

/**
 * 回答反馈条：在流式结束后对助手消息提交点赞/踩及可选评语。
 */

const props = defineProps<{
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: "submit", payload: { rating: number; reasonCode: string; comment: string }): void
}>()

const comment = ref("")

/** 提交评分：正评 reason=helpful，负评 reason=answer_not_accurate */
function submit(rating: number) {
  emit("submit", {
    rating,
    reasonCode: rating > 0 ? "helpful" : "answer_not_accurate",
    comment: comment.value
  })
  comment.value = ""
}
</script>

<template>
  <div class="feedback-bar glass-panel">
    <div>
      <h2 class="section-title">回答反馈</h2>
      <p class="section-subtitle">仅在 `message_end` 之后可操作，确保评分对应最终回答。</p>
    </div>
    <el-input
      v-model="comment"
      :disabled="props.disabled"
      type="textarea"
      :rows="3"
      placeholder="选填：说明为什么觉得有帮助 / 没有帮助"
    />
    <div class="feedback-actions">
      <el-button :disabled="props.disabled" @click="submit(1)">点赞</el-button>
      <el-button type="danger" plain :disabled="props.disabled" @click="submit(-1)">踩</el-button>
    </div>
  </div>
</template>

<style scoped>
.feedback-bar {
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.feedback-actions {
  display: flex;
  gap: 12px;
}
</style>
