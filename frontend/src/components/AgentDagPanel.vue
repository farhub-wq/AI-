<script setup lang="ts">
import { computed } from "vue"
import type { AgentTaskView } from "@/api/types"

/**
 * Agent 任务 DAG 面板：展示串行链、可并行任务，并将 dependsOn ID 解析为任务名。
 */

const props = defineProps<{
  tasks: AgentTaskView[]
}>()

const taskNameById = computed(() => {
  const map = new Map<number, string>()
  for (const task of props.tasks) {
    map.set(task.taskId, task.taskName)
  }
  return map
})

const serialChain = computed(() =>
  props.tasks.filter(task => task.executionMode === "serial" || task.dependsOn.length > 0)
)

const parallelTasks = computed(() =>
  props.tasks.filter(task => task.executionMode === "parallel" && task.dependsOn.length === 0)
)

function modeLabel(mode: string) {
  if (mode === "parallel") return "可并行"
  if (mode === "serial") return "需串行"
  return mode
}

function dependsLabel(task: AgentTaskView) {
  if (!task.dependsOn.length) return "无前置依赖"
  return task.dependsOn
    .map(id => taskNameById.value.get(id) ?? `#${id}`)
    .join(" → ")
}
</script>

<template>
  <div class="dag-panel glass-panel">
    <div>
      <h2 class="section-title">任务 DAG</h2>
      <p class="section-subtitle">串行依赖用任务名展示；可并行任务单独列出，便于评审谁先谁后。</p>
    </div>

    <div v-if="serialChain.length" class="chain-block">
      <h4>串行 / 有依赖任务</h4>
      <ol class="chain-list">
        <li v-for="task in serialChain" :key="task.taskId">
          <strong>{{ task.taskName }}</strong>
          <span class="badge-soft">{{ modeLabel(task.executionMode) }}</span>
          <small class="mono">{{ task.targetService }}</small>
          <p>前置：{{ dependsLabel(task) }}</p>
          <p>{{ task.reason }}</p>
        </li>
      </ol>
    </div>

    <div v-if="parallelTasks.length" class="chain-block">
      <h4>可并行任务</h4>
      <div class="task-list">
        <article v-for="task in parallelTasks" :key="task.taskId" class="task-item">
          <header>
            <strong>{{ task.taskName }}</strong>
            <span class="badge-soft">可并行</span>
          </header>
          <p>{{ task.reason }}</p>
          <small class="mono">目标服务：{{ task.targetService }}</small>
        </article>
      </div>
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

.chain-block h4 {
  margin: 0 0 10px;
}

.chain-list {
  margin: 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.chain-list li {
  border-radius: 16px;
  padding: 12px 14px;
  border: 1px solid rgba(23, 32, 51, 0.08);
  background: rgba(255, 255, 255, 0.82);
}

.chain-list p,
.task-item p,
.task-item small {
  margin: 6px 0 0;
  color: #56657f;
  line-height: 1.6;
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
</style>
