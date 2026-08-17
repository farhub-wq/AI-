# Backend

Spring Boot 3.3 后端，覆盖：

1. JWT 登录
2. 会话 / 消息
3. 知识库上传与文档管理
4. RAG 检索 + Prompt 组装 + LLM 真流式 SSE
5. 反馈与管理看板
6. 运营分析接口
7. 需求拆解 Agent（服务影响面 + 任务 DAG）

## 向量与模型

1. Embedding：优先调用 OpenAI-compatible Embedding API；未配置时自动回退本地 hash 向量
2. 向量存储：自研本地扁平索引（`FAISS_INDEX_DIR`，默认 `data/faiss-index`；Faiss 风格精确余弦 + JSON 落盘，**非 Faiss 原生库**）
3. LLM：OpenAI-compatible Chat Completions，`stream=true` 真流式输出（OkHttp）

> Embedding：请配置有效的 `EMBEDDING_API_KEY`。未配置时回退 hash 向量，仅保证链路可跑，检索质量不适合演示。

## 数据库初始化

目录：`sql/`

| 文件 | 用途 |
| --- | --- |
| `schema.sql` | **完整建表**（12 张表，已合并 `migrate_*.sql` 中的字段与索引）。新库只执行本文件即可。 |
| `migrate_*.sql` | **仅旧库升级**用；新库勿重复执行（会与 `schema.sql` 重复）。 |

### 新库标准流程

```text
执行 schema.sql  →  启动后端  →  DataSeeder 自动灌演示数据
```

1. **建表**：执行 `sql/schema.sql`（见下）。  
2. **启动**：`mvn spring-boot:run`（先配好根目录 `.env`）。  
3. **种子**：空库首次启动由 `DataSeeder` 灌演示数据；SQL **不写**业务 INSERT。

### 建表（新库）

```powershell
# 仓库根目录；Docker 示例
Get-Content .\backend\sql\schema.sql -Raw | docker exec -i ai-cs-mysql mysql -uapp_user -papp_password ai_customer_service

# 或本机客户端（在 backend 目录）
mysql -u app_user -p ai_customer_service < sql/schema.sql
```

表结构与 JPA 实体一一对应；运行时 `hibernate.ddl-auto=update` 仅作兜底，**提交与评审以 `schema.sql` 为准**。

### 初始数据 = DataSeeder（不在 SQL 里 INSERT）

本仓库**不在** `schema.sql` 中写入演示数据。空库首次启动 Spring Boot 时，由：

`com.company.aics.persistence.DataSeeder`

自动完成：

1. 演示账号：`demo@qq.com` / `Passw0rd!`（也可手机号 `13800138000`），角色 `ADMIN`
2. 客服知识库三篇种子文档（classpath `src/main/resources/seed/`）并切块、向量化
3. 技术文档库、服务目录与依赖边（供 Agent 拆解）
4. 一条示例会话与反馈（便于管理后台演示）

若库中已有用户，则跳过重复种子，并对已有文档对齐/重建向量索引。

## 启动

1. 复制根目录 `.env.example` 为 `.env`，填入 `LLM_API_KEY` / `EMBEDDING_API_KEY` 等
2. 启动 MySQL，执行 `sql/schema.sql`（或依赖 `ddl-auto=update` 自动建表）
3. 启动后端（首次空库会跑 DataSeeder）：

```powershell
cd backend
mvn spring-boot:run
```

Swagger：`http://localhost:8080/swagger-ui.html`

## 关键接口

Base path：`/api/v1`

1. `POST /api/v1/auth/login`
2. `POST /api/v1/chat/stream`（SSE）
3. `POST /api/v1/knowledge-bases/{id}/documents`
4. `POST /api/v1/agent/decompose`
5. `GET /api/v1/admin/metrics/overview`
