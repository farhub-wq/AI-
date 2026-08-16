package com.company.aics.application;

import com.company.aics.config.JwtService;
import com.company.aics.domain.DomainModels;
import com.company.aics.persistence.AppDataStore;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 认证应用服务：注册、登录校验密码并签发 JWT，以及按 ID 查询用户。
 * 用户数据读写依赖 MySQL 持久化门面 {@link AppDataStore}。
 */
@Service
public class AuthService {

    private final AppDataStore appDataStore;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * @param appDataStore    MySQL 数据访问门面
     * @param passwordEncoder 密码编码器
     * @param jwtService      JWT 签发服务
     */
    public AuthService(AppDataStore appDataStore, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.appDataStore = appDataStore;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /**
     * 注册新用户：校验邮箱/手机唯一性后写入存储并返回令牌。
     */
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
        return new AuthResult(user, jwtService.createAccessToken(user), jwtService.getExpiresInSeconds());
    }

    /**
     * 使用邮箱或手机号登录；密码不匹配时统一抛出账号或密码错误。
     */
    public AuthResult login(String account, String password) {
        DomainModels.User user = appDataStore.findUserByAccount(account)
                .orElseThrow(() -> new IllegalArgumentException("账号或密码错误。"));

        if (!passwordEncoder.matches(password, user.passwordHash())) {
            throw new IllegalArgumentException("账号或密码错误。");
        }

        return new AuthResult(user, jwtService.createAccessToken(user), jwtService.getExpiresInSeconds());
    }

    /**
     * 按用户 ID 查询；不存在则抛出业务异常。
     */
    public DomainModels.User getUser(Long userId) {
        return appDataStore.findUserById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在。"));
    }

    /**
     * 注册前校验邮箱与手机号未被占用。
     */
    private void ensureEmailAndPhoneUnique(String email, String phone) {
        if (appDataStore.emailExists(email)) {
            throw new IllegalArgumentException("该邮箱已注册。");
        }

        if (StringUtils.hasText(phone) && appDataStore.phoneExists(phone.trim())) {
            throw new IllegalArgumentException("该手机号已注册。");
        }
    }

    /** @return 东八区当前时间 */
    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.ofHours(8));
    }

    /** 认证成功结果：用户、访问令牌与有效期秒数。 */
    public record AuthResult(DomainModels.User user, String accessToken, long expiresIn) {
    }
}
