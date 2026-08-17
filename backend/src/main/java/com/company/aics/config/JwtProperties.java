package com.company.aics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 非密钥配置：有效期与密钥文件路径。
 * 签名密钥不由 .env / JWT_SECRET 注入，见 {@link JwtKeyProvider}。
 */
@ConfigurationProperties(prefix = "security.jwt")
public class JwtProperties {

    /** Access Token 有效期（分钟），生产建议 15–60。 */
    private long accessTokenMinutes = 30;

    /** Refresh Token 有效期（天）。 */
    private long refreshTokenDays = 14;

    /**
     * HMAC 密钥文件路径（Base64 一行）。
     * 默认 {@code data/jwt.hmac.key}（相对进程工作目录，通常为 backend/）。
     */
    private String keyFile = "data/jwt.hmac.key";

    public long getAccessTokenMinutes() {
        return accessTokenMinutes;
    }

    public void setAccessTokenMinutes(long accessTokenMinutes) {
        this.accessTokenMinutes = accessTokenMinutes;
    }

    public long getRefreshTokenDays() {
        return refreshTokenDays;
    }

    public void setRefreshTokenDays(long refreshTokenDays) {
        this.refreshTokenDays = refreshTokenDays;
    }

    public String getKeyFile() {
        return keyFile;
    }

    public void setKeyFile(String keyFile) {
        this.keyFile = keyFile;
    }
}
