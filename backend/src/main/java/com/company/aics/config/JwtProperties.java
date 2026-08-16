package com.company.aics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 安全配置（{@code security.jwt.*}）：签名密钥与访问令牌有效期（分钟）。
 * 由 Spring Boot 绑定配置文件中的同名属性，供 {@link JwtService} 使用。
 */
@ConfigurationProperties(prefix = "security.jwt")
public class JwtProperties {

    private String secret = "replace-with-a-long-development-secret-key-for-jwt-signing";
    private long accessTokenMinutes = 1440;

    /** @return HMAC 签名密钥原文 */
    public String getSecret() {
        return secret;
    }

    /** @param secret 设置签名密钥 */
    public void setSecret(String secret) {
        this.secret = secret;
    }

    /** @return 访问令牌有效期（分钟） */
    public long getAccessTokenMinutes() {
        return accessTokenMinutes;
    }

    /** @param accessTokenMinutes 设置访问令牌有效期（分钟） */
    public void setAccessTokenMinutes(long accessTokenMinutes) {
        this.accessTokenMinutes = accessTokenMinutes;
    }
}
