# API文档

> Base URL：`http://localhost:8080/api/v1`  
> 认证：`Authorization: Bearer <accessToken>`（除注册/登录外）  
> 与当前 `*Controller` 实现对齐。

## 1. 约定

### 1.1 通用 JSON 信封

成功：

```json
{
  "code": 0,
  "message": "ok",
  "data": {}
}
```

失败：`code != 0`，`message` 为可读错误；HTTP 可能为 400/401/429/503 等。

### 1.2 演示账号

- 账号：`demo@qq.com` 或手机号 `13800138000`
- 密码：`Passw0rd!`

---

## 2. 鉴权（Read.md 必含：登录）

### 2.1 注册（邮箱 / 手机号分开，成功后不自动登录）

`POST /auth/register`

邮箱注册（勿传 `phone`；邮箱须为常见后缀如 `@qq.com` / `@163.com` / `@gmail.com`）：

```json
{
  "registerType": "EMAIL",
  "email": "newuser@qq.com",
  "password": "Passw0rd!",
  "displayName": "新用户"
}
```

手机号注册（勿传 `email`；手机号须为 `1` 开头共 11 位）：

```json
{
  "registerType": "PHONE",
  "phone": "13900139000",
  "password": "Passw0rd!",
  "displayName": "新用户"
}
```

响应 `data` 示例（无令牌）：

```json
{
  "userId": 2,
  "displayName": "新用户",
  "registerType": "EMAIL",
  "message": "已注册，请返回登录页登录。",
  "alreadyRegistered": false
}
```

说明：
- 邮箱白名单含 `qq.com`、`163.com`、`126.com`、`gmail.com`、`foxmail.com`、`outlook.com`、`hotmail.com`、`sina.com`、`yeah.net`。
- **唯一性**：邮箱、手机号、昵称（`displayName`）均唯一。
- **幂等**：同一邮箱/手机 + 正确密码重复注册时 `alreadyRegistered=true`，不新建用户，文案仍为「已注册，请返回登录页登录。」；密码不对则报「该邮箱/手机号已注册」。### 2.2 登录

`POST /auth/login`

```json
{
  "account": "demo@qq.com",
  "password": "Passw0rd!"
}
```

响应 `data` 示例：

```json
{
  "accessToken": "eyJ...",
  "tokenType": "Bearer",
  "expiresIn": 1800,
  "refreshToken": "url-safe-random...",
  "refreshExpiresIn": 1209600,
  "user": {
    "id": 1,
    "displayName": "演示用户",
    "email": "demo@qq.com",
    "phone": "13800138000"
  }
}
```

说明：Access 默认约 30 分钟；Refresh 约 14 天。HMAC 密钥不在 `.env`，见 `backend/data/jwt.hmac.key`。

### 2.3 刷新令牌

`POST /auth/refresh`

```json
{ "refreshToken": "..." }
```

返回结构同登录（旧 Refresh 立即作废，实行轮换）。

### 2.4 登出

`POST /auth/logout`

```json
{ "refreshToken": "..." }
```

### 2.5 当前用户

`GET /auth/me`（需 Access Bearer）

---

## 3. 会话（Read.md 必含：会话历史）

### 3.1 创建会话

`POST /conversations`

```json
{
  "title": "关于退货规则咨询",
  "kbId": 1
}
```

`kbId` 可空；后续问答也可自动路由。

### 3.2 会话列表

`GET /conversations?page=1&pageSize=20`

### 3.3 会话详情（含完整消息）

`GET /conversations/{conversationId}`

助手消息可含：`citations`、`intentLabel`、`answerStatus`、`retrievalCount`、`topScore`、`latencyMs`。

---

## 4. 流式问答（Read.md 必含：问答含流式）

### 4.1 接口

`POST /chat/stream`  
`Content-Type: application/json`  
`Accept: text/event-stream`

请求体：

```json
{
  "conversationId": 101,
  "kbId": 0,
  "question": "退货有时间限制吗？",
  "historyRounds": 3
}
```

| 字段 | 说明 |
| --- | --- |
| `conversationId` | 可空；空则后端创建会话 |
| `kbId` | `≤0` 或空：跨客服库自动路由；`>0`：指定知识库 |
| `question` | 必填，最长 500 字 |
| `historyRounds` | 1–10，默认 3 |

业务规则：每日提问上限默认 100（`DAILY_QUESTION_LIMIT`）；无足够检索证据则兜底，不编造。

### 4.2 SSE 事件格式（Read.md 必含）

```text
Content-Type: text/event-stream
```

```text
event: message_start
data: {"messageId":1002,"conversationId":101,"kbId":1,"traceId":"...","intentLabel":"售后问题","evidencePacking":"policy=1, high=2, background=0, ..."}

event: token
data: {"delta":"根据当前知识库，"}

event: token
data: {"delta":"签收后 7 天内可申请无理由退货。"}

event: citation
data: {"items":[{"documentId":12,"documentName":"退换货政策.txt","chunkId":"vec-...","snippet":"签收之日起 7 个自然日内..."}]}

event: message_end
data: {"messageId":1002,"answerStatus":"success","intentLabel":"售后问题","kbId":1,"followUpSuggestions":["退货运费由谁承担？","哪些商品不支持无理由退货？"],"traceId":"...","evidencePacking":"..."}
```

错误：

```text
event: error
data: {"code":"STREAM_FAILED","message":"...","traceId":"..."}
```

`answerStatus`：`success` | `fallback` | `degraded` | `streaming`

---

## 5. 知识库（Read.md 必含：上传文档）

### 5.1 创建知识库

`POST /knowledge-bases`

```json
{
  "name": "客服知识库",
  "kbType": "customer_support",
  "description": "售后政策与 FAQ"
}
```

`kbType` 常用：`customer_support` / `technical_docs`。

### 5.2 列表

`GET /knowledge-bases`

### 5.3 上传文档

`POST /knowledge-bases/{kbId}/documents`  
`multipart/form-data`：`file`（`.txt` / `.md` / `.pdf`），可选 `priority`（如 `policy` / `general` / `engineering`）

响应文档 `status`：先 `processing`，异步向量化后 `ready` 或 `failed`（前端可轮询列表）。

### 5.4 文档列表

`GET /knowledge-bases/{kbId}/documents`

### 5.5 删除文档

`DELETE /knowledge-bases/{kbId}/documents/{documentId}`

同步删除 MySQL 切块与向量索引中对应 `vectorId`。

---

## 6. 反馈（Read.md 必含：反馈提交）

`POST /messages/{messageId}/feedback`

```json
{
  "rating": 1,
  "reasonCode": "helpful",
  "comment": "引用清楚"
}
```

`rating`：`1` 赞 / `-1` 踩。

---

## 7. 管理接口（加分：管理后台）

**权限**：仅 `role=ADMIN` 的用户可调用；普通注册用户为 `USER`，访问返回 HTTP 403（`code=4030`）。  
演示账号 `demo@qq.com` / `Passw0rd!` 种子为 ADMIN。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/admin/metrics/overview` | 今日提问、好评率、兜底率等 |
| GET | `/admin/metrics/daily-questions` | 日提问趋势 |
| GET | `/admin/metrics/feedback` | 反馈分析 |
| GET | `/admin/conversations` | 全量会话 |

---

## 8. 研发变更规划（Read.md 加分项 6）

定位：面向研发团队的**变更规划工作台**（侧栏「研发变更规划」），回答「改哪些微服务 / 谁可并行 / 谁必须串行」；仅输出规划与 DAG，不自动改多仓代码。

### 8.1 创建变更规划

`POST /agent/decompose`

```json
{
  "requirementTitle": "下单成功后自动发送短信",
  "requirementContent": "用户下单完成后，系统应自动向用户手机号发送短信，并在前端成功页展示发送结果。",
  "documentScope": {
    "serviceCodes": ["order-service", "user-service", "notification-service", "mall-web"]
  },
  "changeTicketId": "CHG-2026-001",
  "priority": "P1",
  "requester": "交易产品"
}
```

`changeTicketId` / `priority` / `requester` 为可选；旧客户端只传标题正文与服务范围仍可用。

响应摘要示例：

```json
{
  "planId": 1,
  "status": "success",
  "impactedServices": [
    { "serviceCode": "order-service", "serviceName": "订单服务", "reason": "语义信号：涉及下单/订单状态；命中技术文档：..." },
    { "serviceCode": "notification-service", "serviceName": "通知服务", "reason": "..." }
  ],
  "parallelGroups": [
    ["开放并校验手机号查询", "更新前端成功状态文案"]
  ],
  "missingEvidence": []
}
```

`status`：`success` / `partial` / `failed`。

### 8.2 规划详情

`GET /agent/plans/{planId}`

`data` 在原有字段外增量：

| 字段 | 说明 |
| --- | --- |
| `changeTicketId` / `priority` / `requester` | 变更单元数据 |
| `evidenceHits[]` | 文档命中：`fileName` / `serviceCode` / `score` |
| `dependencyEdgesUsed[]` | 本计划用到的依赖边 |
| `suggestedReleaseOrder[]` | 建议合并/发布顺序（服务码） |
| `reviewChecklist[]` | 人工评审清单 |
| `llmAssistSummary` | LLM 规划摘要（可空） |
| `llmAssistStatus` | `success` / `skipped` / `failed` |
| `planningMode` | `multi_agent_llm`（主路径）/ `rules_fallback`（降级） |
| `agentTrace` | 多 Agent / 反思轨迹步骤 |
| `reflectionRetryCount` | 反思触发的重试次数 |
| `tasks[]` | 另含 `ownerTeam`、`dependencyType` |

说明：主路径为层级流水线 Impact→Reflection→DAG→Reflection→Review→Reflection；否决错误写入 `agent_error_memory`，后续规划注入教训自我修正。失败时降级规则引擎。

### 8.3 规划历史

`GET /agent/plans?page=1&pageSize=20`

### 8.4 服务目录

`GET /agent/service-catalog`

### 8.5 服务依赖边

`GET /agent/service-dependencies`

```json
[
  {
    "fromServiceCode": "order-service",
    "toServiceCode": "notification-service",
    "dependencyType": "event",
    "dependencyDesc": "下单成功事件由通知服务消费"
  }
]
```

---

## 9. HTTP 状态参考

| HTTP | 含义 |
| --- | --- |
| 200 | 成功（业务错误也可能 200 + code≠0，以全局异常映射为准） |
| 400 | 参数校验失败 |
| 401 | 未登录 |
| 429 | 每日提问超限 |
| 503 | 上游 LLM/Embedding 等不可用（`AiServiceException`） |

---

## 10. 实现要点

1. 向量检索与 LLM 仅在后端调用，前端不持有 API Key。  
2. SSE 与 JSON 接口分离；引用在 `citation` 事件一次性下发。  
3. 问答与变更规划均落库，支持历史回放。
