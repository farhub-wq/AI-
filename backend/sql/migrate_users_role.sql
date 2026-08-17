-- 为 users 增加系统角色（已有库手动执行）
-- 注意：新库请直接执行 schema.sql（已含 role 列）。本脚本仅用于旧库升级。
-- USER：普通用户；ADMIN：可访问 /api/v1/admin/**
-- 若列已存在会报错，可忽略后执行 UPDATE

ALTER TABLE users
  ADD COLUMN role VARCHAR(32) NOT NULL DEFAULT 'USER' AFTER display_name;

UPDATE users SET role = 'ADMIN' WHERE email = 'demo@qq.com' OR phone = '13800138000';
