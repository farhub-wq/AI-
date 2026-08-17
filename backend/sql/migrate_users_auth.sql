-- 对齐 schema.sql 中 users 表变更（可重复执行）
-- 注意：新库请直接执行 schema.sql（已含可空 email / display_name 唯一）。本脚本仅用于旧库升级。
-- 1) email 允许为空（手机号注册）
-- 2) display_name 全局唯一
-- 3) 演示账号邮箱改为允许后缀 @qq.com

USE ai_customer_service;

-- 演示账号：旧邮箱不在白名单，更新为 demo@qq.com（若目标邮箱已被占用则跳过）
UPDATE users
SET email = 'demo@qq.com'
WHERE email = 'demo@example.com'
  AND NOT EXISTS (
    SELECT 1 FROM (SELECT id FROM users WHERE email = 'demo@qq.com') t
  );

-- email 改为可空（手机号注册用户可不填邮箱）
ALTER TABLE users
  MODIFY COLUMN email VARCHAR(128) NULL;

-- 昵称唯一索引（已存在则忽略错误由脚本外处理）
-- 先确保无重复昵称再加索引
ALTER TABLE users
  ADD UNIQUE KEY uk_users_display_name (display_name);
