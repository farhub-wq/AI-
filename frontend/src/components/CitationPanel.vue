<script setup lang="ts">
import type { CitationView } from "@/api/types"

/**
 * 知识引用面板：展示当前助手回答关联的文档片段；无引用时显示空状态。
 */

defineProps<{
  citations: CitationView[]
}>()
</script>

<template>
  <div class="citation-panel glass-panel">
    <div>
      <h2 class="section-title">知识引用来源</h2>
      <p class="section-subtitle">SSE 的 `citation` 事件会在回答流结束后返回支撑证据。</p>
    </div>

    <el-empty v-if="citations.length === 0" description="当前回答暂无引用" />

    <div v-else class="citation-list">
      <article v-for="citation in citations" :key="citation.chunkId" class="citation-item">
        <header>
          <strong>{{ citation.documentName }}</strong>
          <span class="mono">{{ citation.chunkId }}</span>
        </header>
        <p>{{ citation.snippet }}</p>
      </article>
    </div>
  </div>
</template>

<style scoped>
.citation-panel {
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.citation-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.citation-item {
  border-radius: 18px;
  padding: 14px;
  border: 1px solid rgba(23, 32, 51, 0.08);
  background: rgba(248, 251, 255, 0.82);
}

.citation-item header {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.citation-item p {
  margin: 10px 0 0;
  line-height: 1.7;
  color: #41506d;
  white-space: pre-wrap;
}
</style>
