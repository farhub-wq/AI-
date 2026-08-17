# AI架构设计


## 1. 设计目标

本项目 AI 架构同时覆盖：

1. 企业知识客服问答（RAG + SSE）
2. 研发需求拆解 Agent（技术库检索 + 服务依赖 DAG）

架构目标：

1. 完整实现上传 → 向量化 → 检索 → Prompt → LLM → 流式返回
2. 回答可引用、可追溯；空检索不编造
3. 处理上下文过长与幻觉（分层证据 + 数字一致性校验）
4. 第 6 点 Agent 作为独立模块落地（与客服 RAG 隔离）

## 2. 总体逻辑架构

```mermaid
flowchart TD
    FE[Vue 3 前端] --> API[Spring Boot API]
    API --> AUTH[鉴权与会话]
    API --> KB[知识库入库]
    API --> RAG[RAG 编排 ChatService]
    API --> OBS[反馈与运营]
    API --> AGENT[AgentPlannerService]

    AUTH --> MYSQL[(MySQL)]
    KB --> MYSQL
    RAG --> MYSQL
    OBS --> MYSQL
    AGENT --> MYSQL

    KB --> VEC[VectorIndexService<br/>内存余弦 + 可选 Qdrant]
    RAG --> VEC
    AGENT --> VEC

    KB --> EMB[Embedding API / 本地回退]
    RAG --> EMB
    RAG --> LLM[OpenAI 兼容 Chat API<br/>OkHttp SSE]
```

说明：Agent **当前不调用 LLM 生成 DAG**，以检索 + 规则/依赖表规划为主，避免无证据空想服务名。

## 3. 技术实现基线（与代码一致）

| 层级 | 实现 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3.3、Spring MVC、Security+JWT、JPA |
| 流式 | `SseEmitter`（`ChatController` / `ChatService`） |
| LLM/Embedding | `OpenAiCompatibleChatClient`、`EmbeddingClient`（OkHttp；非 WebClient） |
| 向量 | `VectorIndexService`：进程内余弦为主，可镜像 Qdrant |
| 前端 | Vue 3 + TS + Vite + Pinia + Element Plus |

## 4. 客服问答 RAG 完整流程

```mermaid
sequenceDiagram
    participant U as 用户
    participant FE as Vue
    participant API as ChatService
    participant IC as IntentClassifier
    participant KB as KnowledgeBaseService
    participant EG as EvidenceGovernanceService
    participant LLM as ChatClient
    participant DB as MySQL

    U->>FE: 提问
    FE->>API: POST /api/v1/chat/stream
    API->>DB: 配额/会话
    API->>IC: 意图分类
    API->>KB: 检索(Top-K)
    KB-->>API: SearchHit[]
    API->>EG: 阈值过滤后 pack 分层
    alt 无证据
        API-->>FE: SSE token 兜底 + answerStatus=fallback
    else 有证据
        API->>LLM: 分层 Prompt 流式
        LLM-->>API: delta
        API-->>FE: event token
        API->>EG: validateAnswer
        alt 校验失败
            API-->>FE: 降级摘要 + degraded
        end
        API-->>FE: citation / message_end
    end
    API->>DB: 保存消息/意图/引用/状态
```

### 4.1 模块职责

| 模块 | 职责 |
| --- | --- |
| 鉴权会话 | 注册登录、JWT、会话历史、每日提问上限 |
| 知识入库 | `.txt/.md/.pdf` 解析切块；`processing→ready/failed` 异步向量化；删除同步清向量 |
| RAG 编排 | 意图 → 检索 → `EvidenceGovernanceService` → Prompt → SSE → 一致性校验 |
| 反馈运营 | 点赞点踩、概览指标、兜底率等 |

### 4.2 增量更新

新文档只 upsert 本文件切块向量，不重建整库；删除按 `vector_id` 清理。。

## 5. Agent（已落地）

代码：`AgentPlannerService`、`POST /api/v1/agent/decompose`、前端「需求拆解」页。

### 5.1  三个问题

| 问题 | 实现方式 |
| --- | --- |
| 改哪些微服务？ | 技术库检索（`serviceCode`）+ 语义信号启发 + 人工范围加权 → `impactedServices` |
| 哪些可并行？ | `executionMode=parallel` 且 `dependsOn` 空 → `parallelGroups` |
| 哪些必须串行？ | 读 `service_dependencies` 两阶段挂边 + 通知依赖订单/用户兜底 |

### 5.2 流程图（与代码步骤一致）

```mermaid
flowchart TD
    A[需求标题+正文] --> B[parseSignals 动作/实体/副作用]
    B --> C[searchTechnicalDocuments]
    C --> D[候选服务打分排序]
    D --> E[listServiceDependencies]
    E --> F[两阶段 buildTasks]
    F --> G[buildParallelGroups]
    G --> H[validatePlan 后校验]
    H --> I[落库 agent_plans.plan_json]
```

### 5.3 示例：下单后发短信

1. 受影响：order / user / notification / mall-web  
2. 可并行：手机号查询、前端成功文案  
3. 串行：订单事件契约 → 通知消费（依赖订单+用户）→ 联调  

边界：输出**规划与 DAG**，不自动改多仓业务代码。

## 6. Prompt 设计

### 6.1 System Prompt（有证据，与代码对齐）

```text
你是电商平台的企业智能客服助手，请使用简体中文回答。
[意图专属约束：售后/投诉/闲聊/产品咨询]
必须遵守：
1. 只能使用「知识库证据」中编号条目，禁止编造政策/价格/时效/运费。
2. 证据分层：must_keep_policy 优先；background 不得单独立规。
3. 依据不足须明确写出并追问。
4. 冲突时政策层优先，禁止把不同编号混写成新规则。
```

### 6.2 User Prompt 拼接方式

1. `用户意图` + `用户问题`  
2. 三层证据块：`[E1] 文档=…；类型=…；内容=…`  
3. 输出要求：关键数字须能在证据中找到；可标注（依据 E1/E2）；禁止猜测  

无证据：不调用自由编造细则；`ChatService` 直接兜底话术。

### 6.3 减幻觉优化思路

| 点 | 做法 |
| --- | --- |
| 证据边界 | 编号强制引用 |
| 分层 | 政策优先 + 背景摘要 |
| 意图 | 售后/投诉更低 temperature |
| 空检索 | 硬阈值，无软塞弱证据 |
| 后校验 | 回答数字须出现在证据中，否则 `degraded` |

### 6.4 Agent 说明

Agent **不以 LLM System Prompt 生成服务列表**；规则锁定服务集合与 `dependsOn`。若未来加 LLM，仅允许润色文案，禁止改边（见 `项目说明.md` §4.5）。

## 7. 向量检索策略（Read.md 要求）

流水线：`KnowledgeBaseService.search*` → 阈值过滤 → `EvidenceGovernanceService.pack`。

| 参数 | 默认 | 配置 | 原因 |
| --- | --- | --- | --- |
| Top-K | 12 | `RAG_TOP_K` | 先宽召回，再阈值+分层收缩 |
| 阈值 | 0.22 | `RAG_SCORE_THRESHOLD` | 兼顾远程 Embedding 与本地哈希；过低易幻觉，过高易空检索 |
| 上下文预算 | 6000 字 | `RAG_MAX_CONTEXT_CHARS` | 政策约 45% / 高相关 40% / 背景 15% |
| 自动路由 | `kbId≤0` | — | 跨客服库选最高分库 |

Agent 技术库：`DEFAULT_TECHNICAL_KB_ID`，缺失时回退 `kb_type=technical_docs`。

## 8. 大规模检索保障

实现类：`EvidenceGovernanceService`。

1. 去重 / 相邻合并  
2. 三层预算入模 + 背景抽取式摘要  
3. 分层 Prompt  
4. `validateAnswer` 数字一致性 → 失败降级  

验证：冲突题看政策层；提高 Top-K 看 `evidencePacking`；追问不存在天数看 `degraded`。

## 9. SSE 事件

| 事件 | 含义 |
| --- | --- |
| `message_start` | messageId / conversationId / kbId / intentLabel / evidencePacking |
| `token` | `{ "delta": "..." }` |
| `citation` | 文档名 + snippet 列表 |
| `message_end` | answerStatus / followUpSuggestions / traceId |
| `error` | 流失败 |

`answerStatus`：`success` / `fallback` / `degraded` / `streaming`

