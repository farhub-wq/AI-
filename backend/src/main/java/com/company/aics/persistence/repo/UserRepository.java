package com.company.aics.persistence.repo;

import com.company.aics.persistence.entity.UserEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 用户表仓储：按邮箱/手机登录查找与唯一性校验。
 */
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    /**
     * 按邮箱或手机号查找用户；同账号多条时取最小 id。
     */
    Optional<UserEntity> findFirstByEmailOrPhoneOrderByIdAsc(String email, String phone);

    /** 邮箱是否已存在。 */
    boolean existsByEmail(String email);

    /** 手机号是否已存在。 */
    boolean existsByPhone(String phone);
}
