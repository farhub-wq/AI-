-- =============================================================================
-- AI 智能客服系统 — 完整建表脚本（与 JPA 实体对齐）
-- =============================================================================
-- 用途：新库一次性建表。已包含 migrate_*.sql 中的全部字段/索引变更，新环境无需再跑 migrate。
-- 初始数据：本文件不含业务种子 INSERT。空库首次启动由
--   com.company.aics.persistence.DataSeeder
-- 写入演示用户、知识库文档、服务目录/依赖，并完成切块与向量索引 upsert。
-- 旧库升级：若表已存在但缺列/缺表，再按需执行同目录 migrate_*.sql。
-- 运行时：application.yml 中 hibernate.ddl-auto=update 可作兜底补列，提交评审请以本脚本为准。
-- =============================================================================

CREATE DATABASE IF NOT EXISTS ai_customer_service
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE ai_customer_service;

-- -----------------------------------------------------------------------------
-- 用户：邮箱/手机二选一注册；display_name、email、phone 各自唯一；role=USER|ADMIN
-- （含 migrate_users_auth / migrate_users_role）
-- -----------------------------------------------------------------------------
CREATE TABLE users (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  email VARCHAR(128) NULL,
  phone VARCHAR(32) NULL,
  password_hash VARCHAR(255) NOT NULL,
  display_name VARCHAR(64) NOT NULL,
  role VARCHAR(32) NOT NULL DEFAULT 'USER',
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_users_email (email),
  UNIQUE KEY uk_users_phone (phone),
  UNIQUE KEY uk_users_display_name (display_name)
);

-- -----------------------------------------------------------------------------
-- 知识库元数据
-- -----------------------------------------------------------------------------
CREATE TABLE knowledge_bases (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  kb_type VARCHAR(64) NOT NULL,
  description TEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- 会话：独立 Session，绑定知识库与末次意图
-- -----------------------------------------------------------------------------
CREATE TABLE conversations (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  kb_id BIGINT NOT NULL,
  title VARCHAR(255) NOT NULL,
  last_intent VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_conversations_user_updated (user_id, updated_at)
);

-- -----------------------------------------------------------------------------
-- 消息：正文、引用、意图、回答状态与检索观测字段
-- -----------------------------------------------------------------------------
CREATE TABLE messages (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  conversation_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  role VARCHAR(32) NOT NULL,
  content LONGTEXT NOT NULL,
  citations_json JSON NULL,
  intent_label VARCHAR(64) NULL,
  answer_status VARCHAR(32) NULL,
  retrieval_count INT NOT NULL DEFAULT 0,
  top_score DECIMAL(10,4) NOT NULL DEFAULT 0,
  latency_ms INT NOT NULL DEFAULT 0,
  trace_id VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_messages_conversation_created (conversation_id, created_at),
  KEY idx_messages_user_created (user_id, created_at)
);

-- -----------------------------------------------------------------------------
-- 消息反馈：点赞/踩 + 可选文字；每条助手消息至多一条
-- -----------------------------------------------------------------------------
CREATE TABLE message_feedback (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  message_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  rating INT NOT NULL,
  reason_code VARCHAR(64) NULL,
  comment VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_message_feedback_message (message_id),
  KEY idx_message_feedback_user (user_id)
);

-- -----------------------------------------------------------------------------
-- 知识文档：上传元数据与处理状态 processing/ready/failed
-- -----------------------------------------------------------------------------
CREATE TABLE knowledge_documents (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  kb_id BIGINT NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  file_ext VARCHAR(16) NOT NULL,
  doc_type VARCHAR(64) NOT NULL,
  content_hash VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  priority VARCHAR(32) NOT NULL DEFAULT 'general',
  service_code VARCHAR(64) NULL,
  uploaded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_knowledge_documents_kb_status (kb_id, status)
);

-- -----------------------------------------------------------------------------
-- 文档切块：vector_id 关联本地向量索引，支撑引用展示与删除同步
-- -----------------------------------------------------------------------------
CREATE TABLE document_chunks (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_id BIGINT NOT NULL,
  kb_id BIGINT NOT NULL,
  vector_id VARCHAR(128) NOT NULL,
  chunk_index INT NOT NULL,
  section_title VARCHAR(128) NULL,
  priority VARCHAR(32) NOT NULL DEFAULT 'general',
  content LONGTEXT NOT NULL,
  metadata_json JSON NULL,
  UNIQUE KEY uk_document_chunks_vector (vector_id),
  KEY idx_document_chunks_document_index (document_id, chunk_index),
  KEY idx_document_chunks_kb (kb_id)
);

-- -----------------------------------------------------------------------------
-- JWT Refresh Token：仅存哈希，支持轮换与吊销
-- -----------------------------------------------------------------------------
CREATE TABLE refresh_tokens (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  token_hash VARCHAR(128) NOT NULL,
  expires_at DATETIME NOT NULL,
  revoked TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_refresh_token_hash (token_hash),
  KEY idx_refresh_user (user_id)
);

-- -----------------------------------------------------------------------------
-- Agent：微服务目录
-- -----------------------------------------------------------------------------
CREATE TABLE service_catalog (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  service_code VARCHAR(64) NOT NULL,
  service_name VARCHAR(128) NOT NULL,
  service_type VARCHAR(32) NOT NULL,
  owner_team VARCHAR(128) NULL,
  description TEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_service_code (service_code)
);

-- -----------------------------------------------------------------------------
-- Agent：服务依赖边（编码表达，非外键 id）
-- -----------------------------------------------------------------------------
CREATE TABLE service_dependencies (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  from_service_code VARCHAR(64) NOT NULL,
  to_service_code VARCHAR(64) NOT NULL,
  dependency_type VARCHAR(32) NOT NULL,
  dependency_desc TEXT NULL,
  KEY idx_service_dep_from (from_service_code),
  KEY idx_service_dep_to (to_service_code)
);

-- -----------------------------------------------------------------------------
-- Agent：需求拆解规划结果（任务/并行组等在 plan_json）
-- -----------------------------------------------------------------------------
CREATE TABLE agent_plans (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  requirement_title VARCHAR(255) NOT NULL,
  requirement_content LONGTEXT NOT NULL,
  status VARCHAR(32) NOT NULL,
  plan_json JSON NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_agent_plans_user_created (user_id, created_at)
);

-- -----------------------------------------------------------------------------
-- Agent：错误记忆（含 migrate_agent_error_memory.sql）
-- -----------------------------------------------------------------------------
CREATE TABLE agent_error_memory (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  agent_role VARCHAR(32) NOT NULL,
  stage VARCHAR(32) NOT NULL,
  error_type VARCHAR(64) NOT NULL,
  error_detail TEXT NOT NULL,
  correction_hint TEXT NULL,
  requirement_title VARCHAR(255) NULL,
  plan_id BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_agent_role_created (agent_role, created_at),
  KEY idx_agent_error_created (created_at)
);
