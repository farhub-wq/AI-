<script setup lang="ts">
import { computed, onMounted, reactive } from "vue"
import { ElMessage } from "element-plus"
import AgentDagPanel from "@/components/AgentDagPanel.vue"
import { useAgentStore } from "@/stores/agent"

/**
 * 需求拆解 Agent 页：录入需求与服务范围，展示受影响服务、并行组、验证步骤与任务 DAG。
 */

const agentStore = useAgentStore()
const form = reactive({
  requirementTitle: "下单成功后自动发送短信",
  requirementContent: "用户下单完成后，系统应自动向用户手机号发送短信，并在前端成功页展示发送结果。",
  serviceCodes: ["order-service", "user-service", "notification-service", "mall-web"] as string[]
})

const currentPlan = computed(() => agentStore.currentPlan)

onMounted(() => {
  agentStore.bootstrap().catch(handleError)
})

/** 提交需求拆解并刷新当前规划结果 */
async function submit() {
  try {
    await agentStore.submitRequirement({
      requirementTitle: form.requirementTitle,
      requirementContent: form.requirementContent,
      serviceCodes: form.serviceCodes
    })
    ElMessage.success("需求拆解完成")
  } catch (error) {
    handleError(error)
  }
}

/** 统一错误提示 */
function handleError(error: unknown) {
  ElMessage.error(error instanceof Error ? error.message : "拆解失败")
}

/** 规划状态码转中文标签 */
function statusLabel(status: string) {
  if (status === "success") return "成功"
  if (status === "partial") return "部分完成"
  if (status === "failed") return "失败"
  return status
}
</script>

<template>
  <div class="agent-page">
    <div class="intro-card glass-panel">
      <h2 class="section-title">面向微服务改造的需求拆解 Agent</h2>
      <p class="section-subtitle">
        输入自然语言需求后，系统会结合技术文档与服务目录，输出受影响服务、依赖关系、并行任务组与验证步骤。
      </p>
    </div>

    <div class="agent-layout">
      <div class="glass-panel form-card">
        <h3 class="section-title">需求输入</h3>
        <el-form label-position="top">
          <el-form-item label="标题">
            <el-input v-model="form.requirementTitle" />
          </el-form-item>
          <el-form-item label="详细说明">
            <el-input v-model="form.requirementContent" type="textarea" :rows="6" />
          </el-form-item>
          <el-form-item label="服务范围">
            <el-select v-model="form.serviceCodes" multiple collapse-tags>
              <el-option
                v-for="service in agentStore.serviceCatalog"
                :key="service.serviceCode"
                :label="`${service.serviceName}（${service.serviceCode}）`"
                :value="service.serviceCode"
              />
            </el-select>
          </el-form-item>
          <el-button type="primary" :loading="agentStore.submitting" @click="submit">执行拆解</el-button>
        </el-form>
      </div>

      <div class="result-column">
        <div class="glass-panel result-card">
          <div class="result-header">
            <div>
              <h3 class="section-title">最新规划</h3>
              <p class="section-subtitle">包含受影响服务、验证步骤与缺失证据提示。</p>
            </div>
            <el-select
              v-if="agentStore.planHistory.length > 0"
              :model-value="currentPlan?.planId"
              style="width: 260px"
              @update:model-value="(value: string | number) => agentStore.selectPlan(Number(value))"
            >
              <el-option
                v-for="item in agentStore.planHistory"
                :key="item.planId"
                :label="`${item.requirementTitle}（${statusLabel(item.status)}）`"
                :value="item.planId"
              />
            </el-select>
          </div>

          <template v-if="currentPlan">
            <div class="status-row">
              <span class="badge-soft">状态：{{ statusLabel(currentPlan.status) }}</span>
              <small class="mono">planId={{ currentPlan.planId }}</small>
            </div>

            <div class="impact-grid">
              <article v-for="service in currentPlan.impactedServices" :key="service.serviceCode" class="impact-card">
                <strong>{{ service.serviceName }}</strong>
                <span class="mono">{{ service.serviceCode }}</span>
                <p>{{ service.reason }}</p>
              </article>
            </div>

            <div class="simple-list">
              <h4>可并行任务组</h4>
              <ul>
                <li v-if="!currentPlan.parallelGroups.length">暂无（本需求可能全为串行依赖）</li>
                <li v-for="(group, index) in currentPlan.parallelGroups" :key="index">{{ group.join(" / ") }}</li>
              </ul>
            </div>

            <div class="simple-list">
              <h4>验证步骤</h4>
              <ul>
                <li v-for="step in currentPlan.validationSteps" :key="step">{{ step }}</li>
              </ul>
            </div>

            <div v-if="currentPlan.missingEvidence.length > 0" class="simple-list warning">
              <h4>缺失证据 / 校验提示</h4>
              <ul>
                <li v-for="item in currentPlan.missingEvidence" :key="item">{{ item }}</li>
              </ul>
            </div>
          </template>
        </div>

        <AgentDagPanel :tasks="currentPlan?.tasks ?? []" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.agent-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.intro-card,
.form-card,
.result-card {
  padding: 20px 22px;
}

.agent-layout {
  display: grid;
  grid-template-columns: 360px minmax(0, 1fr);
  gap: 18px;
}

.result-column {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.result-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
}

.status-row {
  margin-top: 12px;
  display: flex;
  gap: 12px;
  align-items: center;
}

.impact-grid {
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
}

.impact-card {
  border-radius: 18px;
  padding: 14px;
  border: 1px solid rgba(23, 32, 51, 0.08);
  background: rgba(255, 255, 255, 0.82);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.impact-card p,
.simple-list li {
  color: #5a6983;
  line-height: 1.7;
}

.simple-list {
  margin-top: 18px;
}

.simple-list h4 {
  margin: 0 0 8px;
}

.warning {
  color: #9a4e00;
}

@media (max-width: 1100px) {
  .agent-layout {
    grid-template-columns: 1fr;
  }
}
</style>
