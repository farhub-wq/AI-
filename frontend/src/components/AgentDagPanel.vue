<script setup lang="ts">
import type { AgentTaskView } from "@/api/types"

/**
 * Agent 任务 DAG 面板：按任务卡片展示执行模式、目标服务与依赖关系。
 */

defineProps<{
  tasks: AgentTaskView[]
}>()

/** 执行模式码转中文标签 */
function modeLabel(mode: string) {
  if (mode === "parallel") return "可并行"
  if (mode === "serial") return "需串行"
  return mode
}
</script>

<template>
  <div class="dag-panel glass-panel">
    <div>
      <h2 class="section-title">任务 DAG</h2>
      <p class="section-subtitle">展示串行依赖与可并行任务，便于评审讨论。</p>
    </div>

    <div class="task-list">
      <article v-for="task in tasks" :key="task.taskId" class="task-item">
        <header>
          <strong>{{ task.taskName }}</strong>
          <span class="badge-soft">{{ modeLabel(task.executionMode) }}</span>
        </header>
        <p>{{ task.reason }}</p>
        <small class="mono">目标服务：{{ task.targetService }}</small>
        <small class="mono">依赖任务：{{ task.dependsOn.length ? task.dependsOn.join(", ") : "无" }}</small>
      </article>
    </div>
  </div>
</template>

<style scoped>
.dag-panel {
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.task-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
}

.task-item {
  border-radius: 20px;
  padding: 14px;
  border: 1px solid rgba(23, 32, 51, 0.08);
  background: rgba(255, 255, 255, 0.82);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.task-item header {
  display: flex;
  justify-content: space-between;
  gap: 10px;
}

.task-item p,
.task-item small {
  margin: 0;
  color: #56657f;
  line-height: 1.6;
}
</style>
