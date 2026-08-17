package com.company.aics.persistence.repo;

import com.company.aics.persistence.entity.UserEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 用户表仓储：按邮箱/手机登录查找；邮箱、手机号、昵称均需唯一。
 */
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    /**
     * 按邮箱或手机号查找用户；同账号多条时取最小 id。
     */
    Optional<UserEntity> findFirstByEmailOrPhoneOrderByIdAsc(String email, String phone);

    /** 邮箱是否已存在（唯一约束的业务层预检）。 */
    boolean existsByEmail(String email);

    /** 手机号是否已存在（唯一约束的业务层预检）。 */
    boolean existsByPhone(String phone);

    /** 昵称是否已存在（唯一约束的业务层预检）。 */
    boolean existsByDisplayName(String displayName);
}
