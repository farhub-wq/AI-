<script setup lang="ts">
import { onMounted, reactive, ref } from "vue"
import { ElMessage } from "element-plus"
import { useKnowledgeStore } from "@/stores/knowledge"

/**
 * 知识库管理页：创建知识库、切换库、上传/删除文档，并展示切块处理状态。
 */

const knowledgeStore = useKnowledgeStore()
const createDialogVisible = ref(false)
const uploadRef = ref<HTMLInputElement | null>(null)
const form = reactive({
  name: "",
  kbType: "customer_support",
  description: ""
})

onMounted(() => {
  knowledgeStore.bootstrap().catch(handleError)
})

/** 提交新建知识库表单并重置输入 */
async function createKnowledgeBaseAction() {
  try {
    await knowledgeStore.addKnowledgeBase({ ...form })
    createDialogVisible.value = false
    form.name = ""
    form.description = ""
    ElMessage.success("知识库已创建")
  } catch (error) {
    handleError(error)
  }
}

/** 文件选择后上传到当前知识库，结束后清空 input 以便重复选同一文件 */
async function onFilePicked(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) {
    return
  }
  try {
    await knowledgeStore.addDocument(file)
    ElMessage.success("文档已上传，正在处理中")
  } catch (error) {
    handleError(error)
  } finally {
    target.value = ""
  }
}

/** 删除指定文档 */
async function removeDocument(documentId: number) {
  try {
    await knowledgeStore.removeDocument(documentId)
    ElMessage.success("文档已删除")
  } catch (error) {
    handleError(error)
  }
}

/** 统一错误提示 */
function handleError(error: unknown) {
  ElMessage.error(error instanceof Error ? error.message : "操作失败")
}

/** 文档处理状态码转中文 */
function statusLabel(status: string) {
  if (status === "ready") return "就绪"
  if (status === "processing") return "处理中"
  if (status === "failed") return "失败"
  return status
}

/** 知识库类型码转中文标签 */
function kbTypeLabel(kbType: string) {
  if (kbType === "customer_support") return "客服知识库"
  if (kbType === "technical_docs") return "技术文档库"
  return kbType
}
</script>

<template>
  <div class="knowledge-page">
    <div class="toolbar glass-panel">
      <div>
        <span class="badge-soft">知识入库</span>
      </div>

      <div class="toolbar-actions">
        <el-select
          :model-value="knowledgeStore.selectedKbId ?? undefined"
          style="width: 280px"
          @update:model-value="(value: string | number) => knowledgeStore.selectKnowledgeBase(Number(value))"
        >
          <el-option
            v-for="kb in knowledgeStore.knowledgeBases"
            :key="kb.id"
            :label="`${kb.name}（${kbTypeLabel(kb.kbType)}）`"
            :value="kb.id"
          />
        </el-select>
        <el-button @click="createDialogVisible = true">新建知识库</el-button>
        <el-button type="primary" @click="uploadRef?.click()">上传文档</el-button>
        <input ref="uploadRef" hidden type="file" accept=".txt,.md,.pdf" @change="onFilePicked" />
      </div>
    </div>

    <div class="document-grid">
      <article v-for="doc in knowledgeStore.documents" :key="doc.id" class="document-card glass-panel">
        <div class="document-meta">
          <span class="badge-soft">{{ doc.docType }}</span>
          <span class="mono">{{ statusLabel(doc.status) }}</span>
        </div>
        <h3>{{ doc.fileName }}</h3>
        <p>优先级：{{ doc.priority }} · 分片数：{{ doc.chunkCount }}</p>
        <p>服务编码：{{ doc.serviceCode || "无" }}</p>
        <small>{{ new Date(doc.uploadedAt).toLocaleString() }}</small>
        <el-button text type="danger" @click="removeDocument(doc.id)">删除</el-button>
      </article>
    </div>

    <el-dialog v-model="createDialogVisible" title="创建知识库" width="460px">
      <el-form label-position="top">
        <el-form-item label="名称">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.kbType">
            <el-option label="客服知识库" value="customer_support" />
            <el-option label="技术文档库" value="technical_docs" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="createKnowledgeBaseAction">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.knowledge-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.toolbar {
  padding: 20px 22px;
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
}

.toolbar-actions {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}

.document-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 16px;
}

.document-card {
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.document-card h3,
.document-card p,
.document-card small {
  margin: 0;
}

.document-card p,
.document-card small {
  color: #5f6d87;
}

.document-meta {
  display: flex;
  justify-content: space-between;
  gap: 10px;
}

@media (max-width: 960px) {
  .toolbar {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
