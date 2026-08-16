package com.company.aics.api;

import com.company.aics.config.AuthenticatedUser;
import org.springframework.security.core.Authentication;

/**
 * 从 Spring Security {@link Authentication} 中解析当前登录用户；缺失或过期时抛出业务异常。
 * 供各需登录的控制器统一调用，避免重复判空逻辑。
 */
public final class CurrentUserSupport {

    /**
     * 工具类禁止实例化。
     */
    private CurrentUserSupport() {
    }

    /**
     * 要求已认证且 principal 为 {@link AuthenticatedUser}，否则视为未登录/令牌无效。
     *
     * @param authentication Spring Security 认证对象
     * @return 当前登录用户
     */
    public static AuthenticatedUser require(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new IllegalArgumentException("Authentication is required or the token has expired.");
        }
        return user;
    }
}
