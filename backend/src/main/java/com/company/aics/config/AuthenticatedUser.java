package com.company.aics.config;

/**
 * 已认证用户主体：放入 SecurityContext，供控制器通过 {@code Authentication} 取当前用户。
 * 由 JWT 过滤器在校验令牌后构造，仅包含用户 ID 与展示名。
 */
public record AuthenticatedUser(Long userId, String displayName) {
}
