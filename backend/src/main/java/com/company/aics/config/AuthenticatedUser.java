package com.company.aics.config;

/**
 * 已认证用户主体：放入 SecurityContext，供控制器通过 {@code Authentication} 取当前用户。
 * {@code role} 为 USER / ADMIN（写入 JWT claim，用于管理端鉴权）。
 */
public record AuthenticatedUser(Long userId, String displayName, String role) {
}
