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
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 认证服务：邮箱/手机分开注册（不混填）、登录签发短效 Access + Refresh。
 * <p>
 * 唯一性：邮箱、手机号、昵称均唯一。注册具备幂等性——同一账号（邮箱或手机）+ 正确密码
 * 重复提交时不新建用户，直接返回「已注册，请返回登录页登录」。
 */
@Service
public class AuthService {

    /** 基础邮箱格式：本地部分 + @ + 域名。 */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
    );

    /**
     * 允许注册/邮箱登录的常见邮箱后缀（小写，不含 @）。
     * 用户要求限制为常见邮箱，避免随意后缀注册。
     */
    private static final Set<String> ALLOWED_EMAIL_DOMAINS = Set.of(
            "qq.com",
            "163.com",
            "126.com",
            "gmail.com",
            "foxmail.com",
            "outlook.com",
            "hotmail.com",
            "sina.com",
            "yeah.net"
    );

    /** 中国大陆手机号：1 开头共 11 位数字。 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1\\d{10}$");

    private final AppDataStore appDataStore;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SecureRandom secureRandom = new SecureRandom();

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

    /**
     * 按注册类型创建用户：EMAIL 仅邮箱+密码；PHONE 仅手机+密码。
     * <ul>
     *   <li>邮箱 / 手机号 / 昵称均唯一</li>
     *   <li>幂等：同一邮箱或手机 + 正确密码重复注册 → 不插库，返回已注册提示</li>
     *   <li>永不签发 JWT，调用方引导用户去登录页</li>
     * </ul>
     */
    @Transactional
    public RegisterResult register(String registerType, String email, String phone, String password, String displayName) {
        String type = registerType == null ? "" : registerType.trim().toUpperCase(Locale.ROOT);
        String name = displayName == null ? "" : displayName.trim();
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("昵称不能为空。");
        }
        if (!StringUtils.hasText(password) || password.length() < 8) {
            throw new IllegalArgumentException("密码至少 8 位。");
        }

        String normalizedEmail = null;
        String normalizedPhone = null;

        if ("EMAIL".equals(type)) {
            // 邮箱注册通道：禁止附带手机号，避免与手机注册混填
            if (StringUtils.hasText(phone)) {
                throw new IllegalArgumentException("邮箱注册请勿填写手机号，请使用「邮箱注册」。");
            }
            normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
            if (!EMAIL_PATTERN.matcher(normalizedEmail).matches()) {
                throw new IllegalArgumentException("邮箱格式不正确。");
            }
            // 仅允许常见邮箱后缀（如 @qq.com / @163.com / @gmail.com）
            if (!isAllowedEmailDomain(normalizedEmail)) {
                throw new IllegalArgumentException(
                        "仅支持常见邮箱后缀注册：" + allowedEmailSuffixHint() + "。"
                );
            }
            // 幂等：邮箱已存在且密码正确 → 视为重复提交，直接回执，不新建
            Optional<RegisterResult> idempotent = tryIdempotentRegister(normalizedEmail, password, type);
            if (idempotent.isPresent()) {
                return idempotent.get();
            }
            if (appDataStore.emailExists(normalizedEmail)) {
                throw new IllegalArgumentException("该邮箱已注册。");
            }
        } else if ("PHONE".equals(type)) {
            // 手机注册通道：禁止附带邮箱，避免与邮箱注册混填
            if (StringUtils.hasText(email)) {
                throw new IllegalArgumentException("手机号注册请勿填写邮箱，请使用「手机号注册」。");
            }
            normalizedPhone = phone == null ? "" : phone.trim();
            if (!PHONE_PATTERN.matcher(normalizedPhone).matches()) {
                throw new IllegalArgumentException("手机号须为 1 开头的 11 位数字。");
            }
            // 幂等：手机号已存在且密码正确 → 视为重复提交，直接回执，不新建
            Optional<RegisterResult> idempotent = tryIdempotentRegister(normalizedPhone, password, type);
            if (idempotent.isPresent()) {
                return idempotent.get();
            }
            if (appDataStore.phoneExists(normalizedPhone)) {
                throw new IllegalArgumentException("该手机号已注册。");
            }
        } else {
            throw new IllegalArgumentException("注册方式无效，请选择邮箱注册或手机号注册。");
        }

        // 昵称全局唯一：与邮箱/手机号并列的业务约束
        if (appDataStore.displayNameExists(name)) {
            throw new IllegalArgumentException("该昵称已被使用，请更换昵称。");
        }

        // 首次写入；并发冲突由库唯一索引兜底，由全局异常处理转成可读错误
        DomainModels.User user = appDataStore.saveUser(new DomainModels.User(
                null,
                normalizedEmail,
                normalizedPhone,
                passwordEncoder.encode(password),
                name,
                1,
                now()
        ));
        // 首次注册成功：只回执提示，不签发 Access/Refresh
        return new RegisterResult(
                user.id(),
                user.displayName(),
                type,
                "已注册，请返回登录页登录。",
                false
        );
    }

    /**
     * 注册幂等判定：账号（邮箱或手机）已存在且密码匹配时，不新建用户，返回与首次注册一致的成功文案。
     * 密码不匹配则返回 empty，由调用方抛出「已注册」类错误。
     */
    private Optional<RegisterResult> tryIdempotentRegister(String account, String password, String registerType) {
        if (!StringUtils.hasText(account)) {
            return Optional.empty();
        }
        return appDataStore.findUserByAccount(account)
                .filter(user -> passwordEncoder.matches(password, user.passwordHash()))
                .map(user -> new RegisterResult(
                        user.id(),
                        user.displayName(),
                        registerType,
                        "已注册，请返回登录页登录。",
                        true
                ));
    }

    /**
     * 账号（邮箱或手机）登录并签发令牌对。
     * 若账号含 @，须为允许的常见邮箱后缀（演示库已有账号除外，仍按库内记录校验密码）。
     */
    @Transactional
    public AuthResult login(String account, String password) {
        String normalized = account == null ? "" : account.trim();
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException("账号或密码错误。");
        }
        // 手机登录：保留 11 位数字形态；邮箱登录：转小写并校验后缀白名单
        if (normalized.matches("^1\\d{10}$") || normalized.matches("^\\d{11}$")) {
            normalized = normalized.trim();
        } else if (normalized.contains("@")) {
            normalized = normalized.toLowerCase(Locale.ROOT);
            if (!EMAIL_PATTERN.matcher(normalized).matches() || !isAllowedEmailDomain(normalized)) {
                throw new IllegalArgumentException(
                        "邮箱登录仅支持常见后缀：" + allowedEmailSuffixHint() + "。"
                );
            }
        }

        DomainModels.User user = appDataStore.findUserByAccount(normalized)
                .orElseThrow(() -> new IllegalArgumentException("账号或密码错误。"));

        if (!passwordEncoder.matches(password, user.passwordHash())) {
            throw new IllegalArgumentException("账号或密码错误。");
        }

        return issueTokenPair(user);
    }

    /** 使用 Refresh 轮换签发新的 Access + Refresh。 */
    @Transactional
    public AuthResult refresh(String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) {
            throw new IllegalArgumentException("缺少 refreshToken。");
        }
        String hash = sha256(refreshToken.trim());
        RefreshTokenEntity entity = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new IllegalArgumentException("刷新令牌无效。"));

        if (entity.isRevoked() || entity.getExpiresAt().isBefore(LocalDateTime.now())) {
            entity.setRevoked(true);
            refreshTokenRepository.save(entity);
            throw new IllegalArgumentException("刷新令牌已失效，请重新登录。");
        }

        // 轮换：旧 Refresh 立即吊销，再签发新令牌对
        entity.setRevoked(true);
        refreshTokenRepository.save(entity);

        DomainModels.User user = appDataStore.findUserById(entity.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在。"));
        return issueTokenPair(user);
    }

    /** 登出：吊销服务端 Refresh（Access 依赖短过期自然失效）。 */
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

    public DomainModels.User getUser(Long userId) {
        return appDataStore.findUserById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在。"));
    }

    /** 判断邮箱后缀是否在常见邮箱白名单内。 */
    private static boolean isAllowedEmailDomain(String email) {
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) {
            return false;
        }
        String domain = email.substring(at + 1).toLowerCase(Locale.ROOT);
        return ALLOWED_EMAIL_DOMAINS.contains(domain);
    }

    /** 对外提示文案：列出主要允许后缀。 */
    private static String allowedEmailSuffixHint() {
        return "@qq.com、@163.com、@gmail.com、@126.com、@foxmail.com 等";
    }

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

    /** 生成不透明 Refresh 明文，仅存 SHA-256 哈希到库。 */
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

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.ofHours(8));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 注册成功回执（无令牌）；alreadyRegistered=true 表示幂等命中已有账号。 */
    public record RegisterResult(
            Long userId,
            String displayName,
            String registerType,
            String message,
            boolean alreadyRegistered
    ) {
    }

    /** 登录/刷新成功后的双令牌与用户。 */
    public record AuthResult(
            DomainModels.User user,
            String accessToken,
            long expiresIn,
            String refreshToken,
            long refreshExpiresIn
    ) {
    }
}
