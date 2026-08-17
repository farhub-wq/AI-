package com.company.aics.config;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;
import javax.crypto.SecretKey;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * JWT HMAC 密钥提供方：不从 .env 读取。
 * <p>
 * 优先加载本地密钥文件；不存在则 SecureRandom 生成并持久化到 data 目录（已 gitignore）。
 * 生产可通过挂载文件或 {@code security.jwt.key-file} 指向编排注入的密钥路径。
 */
@Component
public class JwtKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyProvider.class);
    /** HS256 至少需要 256 bit 密钥。 */
    private static final int KEY_BYTES = 32;

    private final JwtProperties jwtProperties;
    private SecretKey secretKey;

    /**
     * @param jwtProperties 非密钥配置（含 key-file 路径）
     */
    public JwtKeyProvider(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    /** 启动时加载或创建密钥，供后续签发/验签复用。 */
    @PostConstruct
    void init() {
        Path keyPath = resolveKeyPath();
        try {
            byte[] raw = loadOrCreateKey(keyPath);
            this.secretKey = Keys.hmacShaKeyFor(raw);
            log.info("JWT HMAC key loaded from file strategy: {}", keyPath.toAbsolutePath().normalize());
        } catch (IOException ex) {
            throw new IllegalStateException("无法初始化 JWT 签名密钥文件：" + keyPath, ex);
        }
    }

    /** @return 已初始化的 HMAC 签名密钥 */
    public SecretKey secretKey() {
        return secretKey;
    }

    /** 解析密钥文件路径：优先配置项，否则默认 {@code data/jwt.hmac.key}。 */
    private Path resolveKeyPath() {
        String configured = jwtProperties.getKeyFile();
        if (StringUtils.hasText(configured)) {
            return Paths.get(configured);
        }
        return Paths.get("data", "jwt.hmac.key");
    }

    /**
     * 已有文件则 Base64 解码读取；否则生成随机密钥并写入文件。
     */
    private byte[] loadOrCreateKey(Path keyPath) throws IOException {
        if (Files.exists(keyPath)) {
            String encoded = Files.readString(keyPath, StandardCharsets.UTF_8).trim();
            if (!StringUtils.hasText(encoded)) {
                throw new IOException("JWT 密钥文件为空：" + keyPath);
            }
            return Base64.getDecoder().decode(encoded);
        }

        Files.createDirectories(keyPath.getParent() == null ? Paths.get(".") : keyPath.getParent());
        byte[] generated = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(generated);
        String encoded = Base64.getEncoder().encodeToString(generated);
        Files.writeString(keyPath, encoded, StandardCharsets.UTF_8);
        tightenPermissions(keyPath);
        log.warn("已生成新的 JWT HMAC 密钥文件（仅本地/挂载使用，勿提交仓库）：{}", keyPath.toAbsolutePath());
        return generated;
    }

    /** 在 POSIX 系统上将密钥文件权限收紧为仅属主可读写。 */
    private void tightenPermissions(Path keyPath) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            );
            Files.setPosixFilePermissions(keyPath, perms);
        } catch (Exception ignored) {
            // Windows 等非 POSIX 文件系统忽略
        }
    }
}
