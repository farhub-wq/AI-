package com.company.aics.config;

import com.company.aics.domain.DomainModels;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Access JWT 签发与解析（短效）。
 * Refresh Token 不在本类签发，由 {@link com.company.aics.application.AuthService} 以不透明串+库表哈希管理。
 */
@Service
public class JwtService {

    private final JwtProperties jwtProperties;
    private final JwtKeyProvider jwtKeyProvider;

    /**
     * @param jwtProperties   Access/Refresh 有效期等配置
     * @param jwtKeyProvider  文件型 HMAC 密钥
     */
    public JwtService(JwtProperties jwtProperties, JwtKeyProvider jwtKeyProvider) {
        this.jwtProperties = jwtProperties;
        this.jwtKeyProvider = jwtKeyProvider;
    }

    /**
     * 签发短效 Access Token：subject=用户 id，claim typ=access，带 jti 便于审计。
     */
    public String createAccessToken(DomainModels.User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(user.id()))
                .claim("displayName", user.displayName())
                .claim("typ", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(jwtProperties.getAccessTokenMinutes()))))
                .signWith(jwtKeyProvider.secretKey())
                .compact();
    }

    /**
     * 验签并解析 Access Token；拒绝非 access 类型令牌。
     */
    public AuthenticatedUser parseAccessToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(jwtKeyProvider.secretKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        if (!"access".equals(claims.get("typ", String.class))) {
            throw new IllegalArgumentException("不是访问令牌。");
        }
        return new AuthenticatedUser(
                Long.parseLong(claims.getSubject()),
                claims.get("displayName", String.class)
        );
    }

    /** @return Access Token 有效期（秒），写入登录响应 expiresIn */
    public long getAccessExpiresInSeconds() {
        return Duration.ofMinutes(jwtProperties.getAccessTokenMinutes()).toSeconds();
    }

    /** @return Refresh Token TTL，供落库 expires_at 使用 */
    public Duration refreshTokenTtl() {
        return Duration.ofDays(jwtProperties.getRefreshTokenDays());
    }
}
