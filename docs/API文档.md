# API文档

## 1. 约定

### 1.1 基础信息

1. Base URL：`http://localhost:8080/api/v1`
2. 认证方式：`Authorization: Bearer <token>`
3. 普通接口返回 JSON
4. 流式问答返回 SSE

### 1.2 通用响应

成功：

```json
{
  "code": 0,
  "message": "ok",
  "data": {}
}
```

失败：

```json
{
  "code": 4001,
  "message": "invalid credentials",
  "data": null
}
```

## 2. 鉴权接口

### 2.1 注册

`POST /api/v1/auth/register`

请求体：

```json
{
  "email": "demo@example.com",
  "phone": "",
  "password": "Passw0rd!",
  "displayName": "demo"
}
```

### 2.2 登录

`POST /api/v1/auth/login`

请求体：

```json
{
  "account": "demo@example.com",
  "password": "Passw0rd!"
}
```

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "accessToken": "jwt-token",
    "tokenType": "Bearer",
    "expiresIn": 86400,
    "user": {
      "id": 1,
      "displayName": "demo"
    }
  }
}
```

### 2.3 当前用户

`GET /api/v1/auth/me`

## 3. 会话接口

### 3.1 创建会话

`POST /api/v1/conversations`

请求体：

```json
{
  "title": "关于退货规则咨询",
  "kbId": 1
}
```

### 3.2 查询会话列表

`GET /api/v1/conversations?page=1&pageSize=20`

### 3.3 查询会话详情

`GET /api/v1/conversations/{conversationId}`

响应示例：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": 101,
    "title": "关于退货规则咨询",
    "messages": [
      {
        "id": 1001,
        "role": "user",
        "content": "退货有时间限制吗？",
        "createdAt": "2026-08-13T18:01:00+08:00"
      },
      {
        "id": 1002,
        "role": "assistant",
        "content": "根据当前知识库，签收后 7 天内可申请无理由退货。",
        "citations": [
          {
            "documentId": 12,
            "documentName": "退换货政策.txt",
            "chunkId": "doc12-chunk3",
            "snippet": "签收后 7 天内，商品完好可申请无理由退货。"
          }
        ],
        "createdAt": "2026-08-13T18:01:03+08:00"
      }
    ]
  }
}
```

## 4. 客服问答接口

### 4.1 流式问答

`POST /api/v1/chat/stream`

请求体：

```json
{
  "conversationId": 101,
  "kbId": 1,
  "question": "退货有时间限制吗？",
  "historyRounds": 3
}
```

业务规则：

1. 单次提问不超过 500 字
2. 每个用户每日默认上限 100 次
3. 检索不足时必须返回兜底话术
4. 向量检索与 LLM 调用都在后端完成

### 4.2 SSE 数据格式

Header：

```text
Content-Type: text/event-stream
Cache-Control: no-cache
Connection: keep-alive
```

事件示例：

```text
event: message_start
data: {"messageId":"tmp-1","conversationId":101}

event: token
data: {"delta":"根据当前知识库，"}

event: token
data: {"delta":"签收后 7 天内可申请无理由退货。"}

event: citation
data: {"items":[{"chunkId":"doc12-chunk3","documentName":"退换货政策.txt","snippet":"签收后 7 天内，商品完好可申请无理由退货。"}]}

event: message_end
data: {"messageId":1002,"answerStatus":"success","followUpSuggestions":["退货运费谁承担？","退款多久到账？"]}
```

错误示例：

```text
event: error
data: {"code":"LLM_TIMEOUT","message":"模型响应超时，请稍后重试"}
```

## 5. 知识库接口

### 5.1 创建知识库

`POST /api/v1/knowledge-bases`

### 5.2 查询知识库列表

`GET /api/v1/knowledge-bases`

### 5.3 上传文档

`POST /api/v1/knowledge-bases/{kbId}/documents`

请求类型：

`multipart/form-data`

字段：

1. `file`
2. `priority`，可选，默认 `general`

### 5.4 查询文档列表

`GET /api/v1/knowledge-bases/{kbId}/documents`

### 5.5 删除文档

`DELETE /api/v1/knowledge-bases/{kbId}/documents/{documentId}`

删除语义：

1. 删除 MySQL 文档元数据
2. 删除 `document_chunks`
3. 删除 Qdrant 中关联 points

## 6. 反馈接口

### 6.1 提交反馈

`POST /api/v1/messages/{messageId}/feedback`

请求体：

```json
{
  "rating": -1,
  "reasonCode": "answer_not_accurate",
  "comment": "回答没有说明特殊商品是否可退"
}
```

## 7. 管理接口

### 7.1 问答概览

`GET /api/v1/admin/metrics/overview`

### 7.2 反馈统计

`GET /api/v1/admin/metrics/feedback`

### 7.3 全量会话查询

`GET /api/v1/admin/conversations`

## 8. 第 6 点 Agent 接口

这部分是 README 评估标准第 6 点的系统化落地。

### 8.1 创建需求拆解任务

`POST /api/v1/agent/decompose`

请求体：

```json
{
  "requirementTitle": "用户下单后自动发送短信通知",
  "requirementContent": "当用户完成下单后，系统应自动向用户手机号发送短信通知，并在前端提示发送结果。",
  "documentScope": {
    "serviceCodes": ["order-service", "user-service", "notification-service", "mall-web"]
  }
}
```

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "planId": 9001,
    "status": "success",
    "impactedServices": [
      {
        "serviceCode": "order-service",
        "reason": "负责下单成功事件输出"
      },
      {
        "serviceCode": "notification-service",
        "reason": "负责消费事件并发送短信"
      }
    ],
    "parallelGroups": [
      ["短信模板配置", "前端成功提示文案调整"]
    ]
  }
}
```

### 8.2 查询拆解任务详情

`GET /api/v1/agent/plans/{planId}`

响应示例：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "planId": 9001,
    "requirementTitle": "用户下单后自动发送短信通知",
    "impactedServices": [
      {
        "serviceCode": "order-service",
        "reason": "负责下单成功事件输出"
      },
      {
        "serviceCode": "notification-service",
        "reason": "负责消费事件并发送短信"
      }
    ],
    "tasks": [
      {
        "taskId": 1,
        "taskName": "定义下单成功事件",
        "targetService": "order-service",
        "executionMode": "serial",
        "dependsOn": []
      },
      {
        "taskId": 2,
        "taskName": "实现短信通知消费逻辑",
        "targetService": "notification-service",
        "executionMode": "serial",
        "dependsOn": [1]
      }
    ],
    "validationSteps": [
      "验证订单服务成功发布事件",
      "验证通知服务成功发送短信",
      "验证前端展示成功提示"
    ]
  }
}
```

### 8.3 查询可用服务目录

`GET /api/v1/agent/service-catalog`

用途：

1. 返回系统当前已建模的服务列表
2. 给 Agent 页面做可视化过滤

### 8.4 查询 Agent 规划历史

`GET /api/v1/agent/plans?page=1&pageSize=20`

## 9. 状态码建议

| HTTP | 含义 |
| --- | --- |
| 200 | 请求成功 |
| 400 | 参数错误 |
| 401 | 未登录或 token 失效 |
| 403 | 无权限 |
| 404 | 资源不存在 |
| 409 | 状态冲突 |
| 429 | 超出提问上限 |
| 500 | 服务端异常 |
| 503 | 第三方 LLM / Embedding / 向量服务不可用 |

## 10. 实现建议

1. SSE 接口与普通 JSON 接口分开定义
2. 引用信息不要在每个 token 事件里重复发送
3. 所有 AI 接口都保留 `traceId`
4. Agent 拆解接口要保存规划结果，便于回放与评估
