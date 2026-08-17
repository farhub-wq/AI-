# 前端启动说明

## 技术栈

Vue 3 + TypeScript + Vite + Pinia + Vue Router + Element Plus

## 启动步骤

```powershell
cd frontend
npm install
npm run dev
```

默认访问：`http://localhost:5173`

可通过环境变量覆盖后端地址：

```env
VITE_API_BASE_URL=http://localhost:8080/api/v1
```

## 页面说明

1. 登录 / 注册
2. 智能对话（SSE 流式、引用、反馈、追问）
3. 知识库管理（上传 `.txt/.md/.pdf`、删除同步清向量）
4. 管理后台（指标、近 7 日问答折线、反馈与会话）
5. 需求拆解 Agent（受影响服务、并行组、任务 DAG）

## 演示账号

`demo@qq.com` / `Passw0rd!`
