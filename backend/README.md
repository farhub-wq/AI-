# Backend

Spring Boot 3.3 后端骨架，覆盖：

1. JWT 登录
2. 会话 / 消息
3. 知识库上传与文档管理
4. RAG 检索 + Prompt 组装 + LLM 真流式 SSE
5. 反馈与管理看板
6. 运营分析接口
7. 需求拆解 Agent（服务影响面 + 任务 DAG）

## 向量与模型

1. Embedding：优先调用 OpenAI-compatible Embedding API；未配置时自动回退本地 hash 向量
2. 向量存储：**Faiss 本地文件模式**（`FAISS_INDEX_DIR`，默认 `data/faiss-index`）；精确余弦检索 + 落盘，无需 Chroma/Qdrant 等独立服务
3. LLM：OpenAI-compatible Chat Completions，`stream=true` 真流式输出

## 启动

1. 复制根目录 `.env.example` 为 `.env`，填入 `LLM_API_KEY` 等配置
2. 启动 MySQL（业务库）；向量索引自动写本地文件，无需额外容器
3. 启动：

```powershell
cd backend
mvn spring-boot:run
```

Swagger：`http://localhost:8080/swagger-ui.html`

## 关键接口

1. `POST /api/auth/login`
2. `POST /api/chat/stream`（SSE）
3. `POST /api/knowledge-bases/{id}/documents`
4. `POST /api/agent/plans`
5. `GET /api/admin/dashboard`
