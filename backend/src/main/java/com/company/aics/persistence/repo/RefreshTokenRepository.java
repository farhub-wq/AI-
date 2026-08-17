package com.company.aics.persistence.repo;

import com.company.aics.persistence.entity.RefreshTokenEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Refresh Token 仓储：按哈希查找、按用户清理。
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {

    /** 按 token 哈希精确查找（登录刷新/登出时使用）。 */
    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    /** 删除某用户全部 Refresh（可选扩展：改密时全量作废）。 */
    void deleteByUserId(Long userId);
}
