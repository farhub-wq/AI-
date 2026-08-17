-- Agent 错误记忆表：反思 Agent 落库，供后续规划自我修正
-- 注意：新库请直接执行 schema.sql（已包含本表）。本脚本仅用于旧库补表。
CREATE TABLE IF NOT EXISTS agent_error_memory (
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
  KEY idx_created (created_at)
);
