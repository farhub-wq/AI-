package com.company.aics.application;

import com.company.aics.config.JwtService;
import com.company.aics.domain.DomainModels;
import com.company.aics.persistence.AppDataStore;
import com.company.aics.persistence.entity.RefreshTokenEntity;
import com.company.aics.persistence.repo.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 认证服务：注册/登录签发短效 Access + 可轮换 Refresh；刷新与登出吊销。
 * <p>
 * Refresh 原文仅返回客户端一次，库中只存 SHA-256 哈希，避免库泄露直接冒用。
 */
@Service
public class AuthService {

    private final AppDataStore appDataStore;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * @param appDataStore            用户持久化
     * @param passwordEncoder         密码哈希
     * @param jwtService              Access JWT
     * @param refreshTokenRepository  Refresh 哈希存储
     */
    public AuthService(
            AppDataStore appDataStore,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenRepository refreshTokenRepository
    ) {
        this.appDataStore = appDataStore;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /** 注册新用户并签发 Access + Refresh。 */
    @Transactional
    public AuthResult register(String email, String phone, String password, String displayName) {
        ensureEmailAndPhoneUnique(email, phone);
        DomainModels.User user = appDataStore.saveUser(new DomainModels.User(
                null,
                email,
                StringUtils.hasText(phone) ? phone.trim() : null,
                passwordEncoder.encode(password),
                displayName.trim(),
                1,
                now()
        ));
        return issueTokenPair(user);
    }

    /** 账号（邮箱/手机）登录并签发令牌对。 */
    @Transactional
    public AuthResult login(String account, String password) {
        DomainModels.User user = appDataStore.findUserByAccount(account)
                .orElseThrow(() -> new IllegalArgumentException("账号或密码错误。"));

        if (!passwordEncoder.matches(password, user.passwordHash())) {
            throw new IllegalArgumentException("账号或密码错误。");
        }

        return issueTokenPair(user);
    }

    /**
     * 用 Refresh Token 轮换签发新的 Access + Refresh；旧 Refresh 立即吊销（rotation）。
     */
    @Transactional
    public AuthResult refresh(String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) {
            throw new IllegalArgumentException("缺少 refreshToken。");
        }
        String hash = sha256(refreshToken.trim());
        RefreshTokenEntity entity = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new IllegalArgumentException("刷新令牌无效。"));

        // 已吊销或过期：标记吊销并拒绝，防止重放
        if (entity.isRevoked() || entity.getExpiresAt().isBefore(LocalDateTime.now())) {
            entity.setRevoked(true);
            refreshTokenRepository.save(entity);
            throw new IllegalArgumentException("刷新令牌已失效，请重新登录。");
        }

        entity.setRevoked(true);
        refreshTokenRepository.save(entity);

        DomainModels.User user = appDataStore.findUserById(entity.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在。"));
        return issueTokenPair(user);
    }

    /** 吊销指定 Refresh；用于登出（幂等：找不到则忽略）。 */
    @Transactional
    public void logout(String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) {
            return;
        }
        refreshTokenRepository.findByTokenHash(sha256(refreshToken.trim())).ifPresent(entity -> {
            entity.setRevoked(true);
            refreshTokenRepository.save(entity);
        });
    }

    /** 按用户 ID 查询资料。 */
    public DomainModels.User getUser(Long userId) {
        return appDataStore.findUserById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在。"));
    }

    /** 同时签发短效 Access 与不透明 Refresh。 */
    private AuthResult issueTokenPair(DomainModels.User user) {
        String accessToken = jwtService.createAccessToken(user);
        String refreshToken = createAndStoreRefreshToken(user.id());
        return new AuthResult(
                user,
                accessToken,
                jwtService.getAccessExpiresInSeconds(),
                refreshToken,
                jwtService.refreshTokenTtl().toSeconds()
        );
    }

    /**
     * 生成 URL-safe 随机 Refresh，仅哈希入库，原文返回客户端。
     */
    private String createAndStoreRefreshToken(Long userId) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.setUserId(userId);
        entity.setTokenHash(sha256(raw));
        entity.setExpiresAt(LocalDateTime.now().plus(jwtService.refreshTokenTtl()));
        entity.setRevoked(false);
        entity.setCreatedAt(LocalDateTime.now());
        refreshTokenRepository.save(entity);
        return raw;
    }

    private void ensureEmailAndPhoneUnique(String email, String phone) {
        if (appDataStore.emailExists(email)) {
            throw new IllegalArgumentException("该邮箱已注册。");
        }
        if (StringUtils.hasText(phone) && appDataStore.phoneExists(phone.trim())) {
            throw new IllegalArgumentException("该手机号已注册。");
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.ofHours(8));
    }

    /** Refresh 原文的十六进制 SHA-256，用于等值查询。 */
    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * 认证成功结果。
     *
     * @param user              用户
     * @param accessToken       短效 JWT
     * @param expiresIn         Access 秒数
     * @param refreshToken      不透明 Refresh 原文（仅此响应返回）
     * @param refreshExpiresIn  Refresh 秒数
     */
    public record AuthResult(
            DomainModels.User user,
            String accessToken,
            long expiresIn,
            String refreshToken,
            long refreshExpiresIn
    ) {
    }
}
