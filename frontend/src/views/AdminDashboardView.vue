<script setup lang="ts">
import { computed, onMounted } from "vue"
import { useAdminStore } from "@/stores/admin"

/**
 * 管理后台看板：展示总览指标、近 7 日提问趋势、低分反馈与全量会话。
 */

const adminStore = useAdminStore()

onMounted(() => {
  adminStore.bootstrap().catch(console.error)
})

/** 将 0–1 比率格式化为百分比字符串 */
function ratePercent(value?: number) {
  return `${Math.round((value ?? 0) * 100)}%`
}

/** 把日提问量映射为 SVG polyline 的坐标点字符串 */
const chartPoints = computed(() => {
  const points = adminStore.dailyQuestions
  if (points.length === 0) {
    return ""
  }
  const max = Math.max(...points.map(item => item.questionCount), 1)
  return points
    .map((item, index) => {
      // x 按天均分，y 按最大值归一化（SVG 原点在左上）
      const x = points.length === 1 ? 0 : (index / (points.length - 1)) * 100
      const y = 100 - (item.questionCount / max) * 100
      return `${x},${y}`
    })
    .join(" ")
})
</script>

<template>
  <div class="admin-page">
    <div class="header-card glass-panel">
      <span class="badge-soft">运营看板</span>
    </div>

    <div class="metric-grid">
      <article class="metric-card glass-panel">
        <strong>今日提问数</strong>
        <span>{{ adminStore.overview?.dailyQuestionCount ?? 0 }}</span>
      </article>
      <article class="metric-card glass-panel">
        <strong>好评率</strong>
        <span>{{ ratePercent(adminStore.overview?.positiveFeedbackRate) }}</span>
      </article>
      <article class="metric-card glass-panel">
        <strong>兜底率</strong>
        <span>{{ ratePercent(adminStore.overview?.fallbackRate) }}</span>
      </article>
      <article class="metric-card glass-panel">
        <strong>Agent 规划成功率</strong>
        <span>{{ ratePercent(adminStore.overview?.agentPlanSuccessRate) }}</span>
      </article>
    </div>

    <div class="glass-panel card-pad">
      <h3 class="section-title">近 7 日问答量</h3>
      <p class="section-subtitle">按天统计用户提问次数，便于观察业务波动。</p>
      <div class="chart-wrap">
        <svg viewBox="0 0 100 100" preserveAspectRatio="none" class="trend-chart">
          <polyline
            v-if="chartPoints"
            :points="chartPoints"
            fill="none"
            stroke="#d97706"
            stroke-width="2"
            vector-effect="non-scaling-stroke"
          />
        </svg>
        <div class="chart-labels">
          <span v-for="item in adminStore.dailyQuestions" :key="item.date">
            {{ item.date.slice(5) }}（{{ item.questionCount }}）
          </span>
        </div>
      </div>
    </div>

    <div class="content-grid">
      <div class="glass-panel card-pad">
        <h3 class="section-title">低分回答</h3>
        <p class="section-subtitle">用于诊断薄弱回答与缺失知识。</p>
        <el-table :data="adminStore.feedbackMetrics?.lowRatingIssues ?? []" stripe>
          <el-table-column prop="questionPreview" label="问题" min-width="180" />
          <el-table-column prop="answerPreview" label="回答" min-width="220" />
          <el-table-column prop="reasonCode" label="原因" width="180" />
        </el-table>
      </div>

      <div class="glass-panel card-pad">
        <h3 class="section-title">全量会话</h3>
        <p class="section-subtitle">快速浏览近期用户会话与消息模式。</p>
        <el-table :data="adminStore.conversations" stripe>
          <el-table-column prop="title" label="会话" min-width="180" />
          <el-table-column prop="userDisplayName" label="用户" width="120" />
          <el-table-column prop="kbName" label="知识库" width="160" />
          <el-table-column prop="lastMessagePreview" label="最近消息" min-width="220" />
        </el-table>
      </div>
    </div>
  </div>
</template>

<style scoped>
.admin-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.header-card,
.card-pad {
  padding: 20px 22px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.metric-card {
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.metric-card span {
  font-size: 34px;
  font-weight: 700;
}

.content-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 18px;
}

.chart-wrap {
  margin-top: 16px;
}

.trend-chart {
  width: 100%;
  height: 180px;
  background: linear-gradient(180deg, rgba(255, 247, 237, 0.9), rgba(255, 255, 255, 0.6));
  border-radius: 16px;
  border: 1px solid rgba(23, 32, 51, 0.08);
}

.chart-labels {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  color: #5f6d87;
  font-size: 13px;
}

@media (max-width: 960px) {
  .metric-grid {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
