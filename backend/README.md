# 后端启动说明

## 技术栈

Java 21 + Spring Boot 3.3

## 已实现能力

1. 注册登录（JWT）
2. 会话与消息
3. 知识库上传 / 解析 / 切块 / 向量化 / 删除同步
4. RAG 检索 + Prompt 组装 + LLM 真流式 SSE
5. 意图识别、追问建议、回答反馈
6. 管理后台指标与近 7 日问答趋势
7. 需求拆解 Agent（服务影响面 + 任务 DAG）

## 向量与模型

1. Embedding：优先调用 OpenAI-compatible Embedding API；未配置时自动回退本地 hash 向量
2. 向量存储：内存余弦索引必开；若 `QDRANT_URL` 可用则同步写入 Qdrant
3. LLM：OpenAI-compatible Chat Completions，`stream=true` 真流式输出

## 启动

1. 复制根目录 `.env.example` 为 `.env`，填入 `LLM_API_KEY` 等配置
2. 可选启动 MySQL / Qdrant（当前业务默认内存存储，Qdrant 可选增强）
3. 启动：

```powershell
cd backend
mvn spring-boot:run
```

Swagger：`http://localhost:8080/swagger-ui.html`

## 关键配置项

见根目录 `.env.example`：

1. `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_CHAT_MODEL`
2. `EMBEDDING_BASE_URL` / `EMBEDDING_API_KEY` / `EMBEDDING_MODEL`
3. `QDRANT_URL` / `QDRANT_COLLECTION`
4. `RAG_TOP_K` / `RAG_SCORE_THRESHOLD` / `DAILY_QUESTION_LIMIT`
