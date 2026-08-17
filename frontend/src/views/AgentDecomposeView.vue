<script setup lang="ts">
import { computed, onMounted, reactive } from "vue"
import { ElMessage } from "element-plus"
import AgentDagPanel from "@/components/AgentDagPanel.vue"
import { useAgentStore } from "@/stores/agent"

/**
 * 研发变更规划工作台：
 * 变更单 + 需求 → 影响面 / 依赖拓扑 / 并行串行 DAG / 发布顺序 / 人工评审清单。
 * 对齐 Read.md 加分项 6：改谁、谁可并行、谁必须串行。
 */

const agentStore = useAgentStore()
const form = reactive({
  changeTicketId: "CHG-2026-001",
  priority: "P1",
  requester: "",
  requirementTitle: "下单成功后自动发送短信",
  requirementContent: "用户下单完成后，系统应自动向用户手机号发送短信，并在前端成功页展示发送结果。",
  serviceCodes: ["order-service", "user-service", "notification-service", "mall-web"] as string[]
})

const currentPlan = computed(() => agentStore.currentPlan)

const catalogNameByCode = computed(() => {
  const map = new Map<string, string>()
  for (const item of agentStore.serviceCatalog) {
    map.set(item.serviceCode, item.serviceName)
  }
  return map
})

onMounted(() => {
  agentStore.bootstrap().catch(handleError)
})

/** 生成变更规划并刷新详情 */
async function submit() {
  try {
    await agentStore.submitRequirement({
      requirementTitle: form.requirementTitle,
      requirementContent: form.requirementContent,
      serviceCodes: form.serviceCodes,
      changeTicketId: form.changeTicketId || undefined,
      priority: form.priority || undefined,
      requester: form.requester || undefined
    })
    ElMessage.success("变更规划已生成")
  } catch (error) {
    handleError(error)
  }
}

function handleError(error: unknown) {
  ElMessage.error(error instanceof Error ? error.message : "规划失败")
}

function statusLabel(status: string) {
  if (status === "success") return "成功"
  if (status === "partial") return "部分完成"
  if (status === "failed") return "失败"
  return status
}

function depTypeLabel(type: string) {
  if (type === "event") return "事件"
  if (type === "data") return "数据"
  if (type === "api") return "API"
  if (type === "config") return "配置"
  return type
}

function serviceLabel(code: string) {
  return catalogNameByCode.value.get(code) ?? code
}

function llmAssistLabel(status: string) {
  if (status === "success") return "已润色"
  if (status === "failed") return "失败已降级"
  if (status === "skipped") return "已跳过"
  return status
}

function planningModeLabel(mode: string | null | undefined) {
  if (mode === "multi_agent_llm") return "多Agent LLM"
  if (mode === "rules_fallback") return "规则降级"
  return mode || "未知"
}
</script>

<template>
  <div class="agent-page">
    <div class="intro-card glass-panel">
      <h2 class="section-title">研发变更规划工作台</h2>
      <p class="section-subtitle">
        面向真实研发场景：层级多 Agent 流水线（Impact → Reflection → DAG → Reflection → Review），结合技术文档与依赖表判断改哪些微服务、并行与串行；错误记忆落库供自我修正。
        LLM 不可用时自动降级规则路径。（仅规划，不自动改多仓代码。）
      </p>
    </div>

    <div class="agent-layout">
      <div class="glass-panel form-card">
        <h3 class="section-title">变更需求输入</h3>
        <el-form label-position="top">
          <el-form-item label="变更单号">
            <el-input v-model="form.changeTicketId" placeholder="如 CHG-2026-001" />
          </el-form-item>
          <el-form-item label="优先级">
            <el-select v-model="form.priority" style="width: 100%">
              <el-option label="P0（紧急）" value="P0" />
              <el-option label="P1（高）" value="P1" />
              <el-option label="P2（普通）" value="P2" />
            </el-select>
          </el-form-item>
          <el-form-item label="提出人">
            <el-input v-model="form.requester" placeholder="可选，如产品/研发负责人" />
          </el-form-item>
          <el-form-item label="需求标题">
            <el-input v-model="form.requirementTitle" />
          </el-form-item>
          <el-form-item label="详细说明">
            <el-input v-model="form.requirementContent" type="textarea" :rows="6" />
          </el-form-item>
          <el-form-item label="服务范围（选定后严格限定；留空则全量）">
            <el-select v-model="form.serviceCodes" multiple collapse-tags clearable placeholder="不选=全目录；选定后只能在范围内规划">
              <el-option
                v-for="service in agentStore.serviceCatalog"
                :key="service.serviceCode"
                :label="`${service.serviceName}（${service.serviceCode}）`"
                :value="service.serviceCode"
              />
            </el-select>
          </el-form-item>
          <el-button type="primary" :loading="agentStore.submitting" @click="submit">
            生成变更规划
          </el-button>
        </el-form>
      </div>

      <div class="result-column">
        <div class="glass-panel result-card">
          <div class="result-header">
            <div>
              <h3 class="section-title">规划结果</h3>
              <p class="section-subtitle">影响面、依赖、发布顺序与评审清单。</p>
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
              <span v-if="currentPlan.changeTicketId" class="badge-soft">
                变更单：{{ currentPlan.changeTicketId }}
              </span>
              <span v-if="currentPlan.priority" class="badge-soft">{{ currentPlan.priority }}</span>
              <span v-if="currentPlan.planningMode" class="badge-soft">
                规划模式：{{ planningModeLabel(currentPlan.planningMode) }}
              </span>
              <span v-if="(currentPlan.reflectionRetryCount ?? 0) > 0" class="badge-soft">
                反思重试：{{ currentPlan.reflectionRetryCount }}
              </span>
              <span v-if="currentPlan.llmAssistStatus" class="badge-soft">
                LLM：{{ llmAssistLabel(currentPlan.llmAssistStatus) }}
              </span>
              <small v-if="currentPlan.requester">提出人：{{ currentPlan.requester }}</small>
              <small class="mono">planId={{ currentPlan.planId }}</small>
            </div>

            <section v-if="currentPlan.llmAssistSummary" class="section-block">
              <h4>LLM 规划摘要</h4>
              <p class="assist-summary">{{ currentPlan.llmAssistSummary }}</p>
            </section>

            <section v-if="(currentPlan.agentTrace?.length ?? 0) > 0" class="section-block">
              <h4>Agent / 反思轨迹</h4>
              <ol class="simple-list">
                <li v-for="(step, idx) in currentPlan.agentTrace" :key="idx">{{ step }}</li>
              </ol>
            </section>

            <!-- 1. 改哪些服务 -->
            <section class="section-block">
              <h4>1. 改哪些微服务</h4>
              <div class="impact-grid">
                <article
                  v-for="service in currentPlan.impactedServices"
                  :key="service.serviceCode"
                  class="impact-card"
                >
                  <strong>{{ service.serviceName }}</strong>
                  <span class="mono">{{ service.serviceCode }}</span>
                  <p>{{ service.reason }}</p>
                </article>
              </div>
              <div v-if="(currentPlan.evidenceHits?.length ?? 0) > 0" class="simple-list">
                <h5>文档证据（可追溯）</h5>
                <ul>
                  <li v-for="(hit, idx) in currentPlan.evidenceHits" :key="`${hit.fileName}-${idx}`">
                    {{ hit.fileName }} → {{ hit.serviceCode }}
                    <span class="mono">（score={{ hit.score }}）</span>
                  </li>
                </ul>
              </div>
            </section>

            <!-- 2. 依赖与串行依据 -->
            <section class="section-block">
              <h4>2. 依赖与串行依据</h4>
              <div class="simple-list">
                <h5>本计划用到的依赖边</h5>
                <ul>
                  <li v-if="!(currentPlan.dependencyEdgesUsed?.length)">暂无（可能全为并行或依赖表未命中）</li>
                  <li
                    v-for="(edge, idx) in currentPlan.dependencyEdgesUsed"
                    :key="`used-${idx}`"
                  >
                    <span class="mono">{{ edge.fromServiceCode }}</span>
                    →
                    <span class="mono">{{ edge.toServiceCode }}</span>
                    <span class="badge-soft">{{ depTypeLabel(edge.dependencyType) }}</span>
                    <span v-if="edge.dependencyDesc"> — {{ edge.dependencyDesc }}</span>
                  </li>
                </ul>
              </div>
              <div class="simple-list">
                <h5>全量服务依赖目录</h5>
                <ul>
                  <li
                    v-for="(edge, idx) in agentStore.serviceDependencies"
                    :key="`all-${idx}`"
                  >
                    <span class="mono">{{ edge.fromServiceCode }}</span>
                    →
                    <span class="mono">{{ edge.toServiceCode }}</span>
                    <span class="badge-soft">{{ depTypeLabel(edge.dependencyType) }}</span>
                    <span v-if="edge.dependencyDesc"> — {{ edge.dependencyDesc }}</span>
                  </li>
                </ul>
              </div>
            </section>

            <!-- 3. 可并行 -->
            <section class="section-block">
              <h4>3. 可并行任务组</h4>
              <ul class="plain-list">
                <li v-if="!currentPlan.parallelGroups.length">暂无（本需求可能全为串行依赖）</li>
                <li v-for="(group, index) in currentPlan.parallelGroups" :key="index">
                  {{ group.join(" / ") }}
                </li>
              </ul>
            </section>

            <!-- 4. 建议发布顺序 -->
            <section class="section-block">
              <h4>4. 建议发布 / 合并顺序</h4>
              <ol v-if="(currentPlan.suggestedReleaseOrder?.length ?? 0) > 0" class="release-steps">
                <li v-for="(code, index) in currentPlan.suggestedReleaseOrder" :key="code">
                  <span class="step-index">{{ index + 1 }}</span>
                  <strong>{{ serviceLabel(code) }}</strong>
                  <span class="mono">{{ code }}</span>
                </li>
              </ol>
              <p v-else class="muted">暂无建议顺序（影响面为空时会出现）。</p>
            </section>

            <!-- 5. 验收与人工评审 -->
            <section class="section-block">
              <h4>5. 验收与人工评审</h4>
              <div class="simple-list">
                <h5>验证步骤</h5>
                <ul>
                  <li v-for="step in currentPlan.validationSteps" :key="step">{{ step }}</li>
                </ul>
              </div>
              <div class="simple-list">
                <h5>人工评审清单</h5>
                <ul>
                  <li v-if="!(currentPlan.reviewChecklist?.length)">暂无</li>
                  <li v-for="item in currentPlan.reviewChecklist" :key="item">{{ item }}</li>
                </ul>
              </div>
              <div v-if="currentPlan.missingEvidence.length > 0" class="simple-list warning">
                <h5>缺失证据 / 校验提示</h5>
                <ul>
                  <li v-for="item in currentPlan.missingEvidence" :key="item">{{ item }}</li>
                </ul>
              </div>
            </section>
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
  align-items: flex-start;
  margin-bottom: 12px;
}

.status-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-bottom: 16px;
}

.section-block {
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid rgba(23, 32, 51, 0.08);
}

.assist-summary {
  margin: 8px 0 0;
  line-height: 1.6;
  color: #334155;
  white-space: pre-wrap;
}

.section-block h4 {
  margin: 0 0 12px;
  font-size: 16px;
}

.section-block h5 {
  margin: 0 0 8px;
  font-size: 13px;
  color: #4b5872;
}

.impact-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px;
}

.impact-card {
  border-radius: 16px;
  padding: 14px;
  border: 1px solid rgba(23, 32, 51, 0.08);
  background: rgba(255, 255, 255, 0.85);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.impact-card p {
  margin: 0;
  color: #56657f;
  line-height: 1.55;
  font-size: 13px;
}

.simple-list,
.plain-list {
  margin-top: 12px;
}

.simple-list ul,
.plain-list {
  margin: 0;
  padding-left: 18px;
  color: #455468;
  line-height: 1.7;
}

.simple-list.warning {
  color: #92400e;
}

.release-steps {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.release-steps li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.82);
  border: 1px solid rgba(23, 32, 51, 0.08);
}

.step-index {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: rgba(37, 99, 235, 0.12);
  color: #1d4ed8;
  font-size: 12px;
  font-weight: 700;
}

.muted {
  margin: 0;
  color: #7a879c;
}

@media (max-width: 1100px) {
  .agent-layout {
    grid-template-columns: 1fr;
  }
}
</style>
