package com.company.aics.config;

import com.company.aics.domain.DomainModels;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * JWT 签发与解析：以用户 id 为 subject，附带 displayName；HMAC 签名。
 * 供登录/注册签发访问令牌，以及过滤器校验并还原 {@link AuthenticatedUser}。
 */
@Service
public class JwtService {

    private final JwtProperties jwtProperties;
    private SecretKey secretKey;

    /**
     * @param jwtProperties JWT 密钥与有效期配置
     */
    public JwtService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    /**
     * 根据配置密钥初始化 HMAC 签名密钥。
     */
    @PostConstruct
    void init() {
        this.secretKey = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 为登录/注册成功的用户签发访问令牌。
     *
     * @param user 已认证用户领域对象
     * @return 紧凑格式 JWT 字符串
     */
    public String createAccessToken(DomainModels.User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.id()))
                .claim("displayName", user.displayName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(jwtProperties.getAccessTokenMinutes()))))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 校验签名后还原为 {@link AuthenticatedUser}；失败由调用方捕获。
     *
     * @param token Bearer 令牌原文（不含前缀）
     * @return 解析出的认证主体
     */
    public AuthenticatedUser parseAccessToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new AuthenticatedUser(
                Long.parseLong(claims.getSubject()),
                claims.get("displayName", String.class)
        );
    }

    /**
     * @return 访问令牌有效期对应的秒数，供登录响应返回
     */
    public long getExpiresInSeconds() {
        return Duration.ofMinutes(jwtProperties.getAccessTokenMinutes()).toSeconds();
    }
}
