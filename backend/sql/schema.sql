-- 用户表：邮箱注册与手机号注册分开（email/phone 二选一可空）
-- 唯一性：email、phone、display_name 各自唯一（支撑幂等注册与防重复账号）
CREATE TABLE users (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  email VARCHAR(128) NULL,              -- 邮箱注册必填；常见后缀由业务校验；唯一
  phone VARCHAR(32) NULL,               -- 手机注册必填；1 开头 11 位由业务校验；唯一
  password_hash VARCHAR(255) NOT NULL,
  display_name VARCHAR(64) NOT NULL,    -- 昵称全局唯一
  role VARCHAR(32) NOT NULL DEFAULT 'USER', -- USER / ADMIN；仅 ADMIN 可访问 /api/v1/admin/**
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_users_email (email),
  UNIQUE KEY uk_users_phone (phone),
  UNIQUE KEY uk_users_display_name (display_name)
);

CREATE TABLE conversations (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  kb_id BIGINT NOT NULL,
  title VARCHAR(255) NOT NULL,
  last_intent VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

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
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE knowledge_bases (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  kb_type VARCHAR(64) NOT NULL,
  description TEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

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

CREATE TABLE agent_plans (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  requirement_title VARCHAR(255) NOT NULL,
  requirement_content LONGTEXT NOT NULL,
  status VARCHAR(32) NOT NULL,
  plan_json JSON NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

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
