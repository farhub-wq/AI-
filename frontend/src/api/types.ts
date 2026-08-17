/**
 * 前后端共享的视图模型类型定义：登录、会话、知识库、管理看板与 Agent 规划等接口的响应结构。
 */

/** 当前登录用户的展示信息（邮箱/手机取决于注册方式，可为空） */
export interface UserView {
  id: number
  displayName: string
  /** 邮箱注册用户；手机注册通常为空 */
  email?: string | null
  /** 手机注册用户；邮箱注册通常为空 */
  phone?: string | null
}

/**
 * 注册成功响应：不签发令牌。
 * alreadyRegistered=true 表示幂等命中（重复提交同一账号+正确密码），未新建用户。
 * 前端应提示 message 并切回登录页，不可当作已登录。
 */
export interface RegisterResponse {
  userId: number
  displayName: string
  registerType: "EMAIL" | "PHONE" | string
  message: string
  /** 幂等命中：账号已存在且密码正确 */
  alreadyRegistered?: boolean
}

/** 登录成功后的令牌与用户信息（含 Refresh；注册接口不会返回本结构） */
export interface LoginResponse {
  accessToken: string
  tokenType: string
  /** Access 有效秒数 */
  expiresIn: number
  refreshToken: string
  /** Refresh 有效秒数 */
  refreshExpiresIn: number
  user: UserView
}

/** 助手回答引用的知识片段 */
export interface CitationView {
  documentId: number
  documentName: string
  chunkId: string
  snippet: string
}

/** 会话中的单条消息（用户或助手） */
export interface MessageView {
  id: number
  role: "user" | "assistant"
  content: string
  citations: CitationView[]
  intentLabel?: string | null
  answerStatus?: string | null
  retrievalCount?: number | null
  topScore?: number | null
  latencyMs?: number | null
  createdAt: string
}

/** 会话列表中的摘要项 */
export interface ConversationSummaryView {
  id: number
  title: string
  kbId: number
  lastIntent?: string | null
  lastMessagePreview: string
  updatedAt: string
}

/** 会话详情（含完整消息列表） */
export interface ConversationDetailView {
  id: number
  title: string
  kbId: number
  messages: MessageView[]
}

/** 知识库基本信息 */
export interface KnowledgeBaseView {
  id: number
  name: string
  kbType: string
  description?: string | null
  createdAt: string
}

/** 知识库内已上传文档的元数据与处理状态 */
export interface KnowledgeDocumentView {
  id: number
  kbId: number
  fileName: string
  fileExt: string
  docType: string
  status: string
  priority: string
  serviceCode?: string | null
  chunkCount: number
  uploadedAt: string
}

/** 对助手消息提交反馈后的回执 */
export interface FeedbackResponse {
  messageId: number
  rating: number
  reasonCode: string
  comment?: string | null
  createdAt: string
}

/** 管理后台总览指标 */
export interface MetricsOverviewView {
  dailyQuestionCount: number
  assistantMessageCount: number
  feedbackCount: number
  positiveFeedbackRate: number
  fallbackRate: number
  agentPlanSuccessRate: number
}

/** 按日统计的提问量数据点 */
export interface DailyQuestionPointView {
  date: string
  questionCount: number
}

/** 低分反馈问题条目，便于定位薄弱回答 */
export interface FeedbackIssueView {
  messageId: number
  conversationId: number
  questionPreview: string
  answerPreview: string
  reasonCode: string
  comment?: string | null
  createdAt: string
}

/** 反馈聚合指标与低分列表 */
export interface FeedbackMetricsView {
  positiveCount: number
  negativeCount: number
  lowRatingIssues: FeedbackIssueView[]
}

/** 管理端会话列表项 */
export interface AdminConversationView {
  conversationId: number
  title: string
  userDisplayName: string
  kbName: string
  lastMessagePreview: string
  updatedAt: string
}

/** Agent 识别出的受影响微服务 */
export interface ImpactedServiceView {
  serviceCode: string
  serviceName: string
  reason: string
}

/** Agent 规划中的单个改造任务 */
export interface AgentTaskView {
  taskId: number
  taskName: string
  targetService: string
  executionMode: string
  dependsOn: number[]
  reason: string
}

/** 提交需求拆解后的创建回执（摘要） */
export interface AgentPlanCreateResponse {
  planId: number
  status: string
  impactedServices: ImpactedServiceView[]
  parallelGroups: string[][]
  missingEvidence: string[]
}

/** Agent 规划完整详情 */
export interface AgentPlanDetailView {
  planId: number
  requirementTitle: string
  requirementContent: string
  status: string
  impactedServices: ImpactedServiceView[]
  parallelGroups: string[][]
  tasks: AgentTaskView[]
  validationSteps: string[]
  missingEvidence: string[]
  createdAt: string
}

/** 历史规划列表摘要 */
export interface AgentPlanSummaryView {
  planId: number
  requirementTitle: string
  status: string
  impactedServiceCount: number
  createdAt: string
}

/** 可供选择的微服务目录项 */
export interface ServiceCatalogView {
  serviceCode: string
  serviceName: string
  serviceType: string
  ownerTeam?: string | null
  description?: string | null
}
