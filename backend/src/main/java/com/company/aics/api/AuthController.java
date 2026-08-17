package com.company.aics.api;

import com.company.aics.api.ApiModels.LoginResponse;
import com.company.aics.api.ApiModels.RegisterResponse;
import com.company.aics.application.AuthService;
import com.company.aics.config.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证 API：邮箱/手机分开注册（不自动登录）、登录签发双令牌、刷新与登出。
 * 邮箱须为常见后缀；注册成功不返回 token。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 注册：registerType=EMAIL|PHONE。
     * EMAIL 仅邮箱+密码且后缀白名单；PHONE 仅手机+密码；
     * 邮箱/手机/昵称唯一；同一账号重复注册（密码正确）幂等成功；成功不签发令牌。
     */
    @PostMapping("/register")
    public ApiEnvelope<RegisterResponse> register(@Valid @RequestBody ApiModels.RegisterRequest request) {
        AuthService.RegisterResult result = authService.register(
                request.registerType(),
                request.email(),
                request.phone(),
                request.password(),
                request.displayName()
        );
        // 仅回执注册结果（含幂等标记），前端应切回登录 Tab，不写 token
        return ApiEnvelope.success(new RegisterResponse(
                result.userId(),
                result.displayName(),
                result.registerType(),
                result.message(),
                result.alreadyRegistered()
        ));
    }

    /** 账号（允许后缀的邮箱或手机）登录并返回 Access + Refresh。 */
    @PostMapping("/login")
    public ApiEnvelope<LoginResponse> login(@Valid @RequestBody ApiModels.LoginRequest request) {
        AuthService.AuthResult result = authService.login(request.account(), request.password());
        return ApiEnvelope.success(toLoginResponse(result));
    }

    /** 用 Refresh 换取新的双令牌（旧 Refresh 吊销）。 */
    @PostMapping("/refresh")
    public ApiEnvelope<LoginResponse> refresh(@Valid @RequestBody ApiModels.RefreshTokenRequest request) {
        return ApiEnvelope.success(toLoginResponse(authService.refresh(request.refreshToken())));
    }

    /** 登出：吊销 Refresh；无 body 时视为仅清客户端态。 */
    @PostMapping("/logout")
    public ApiEnvelope<Void> logout(@RequestBody(required = false) ApiModels.LogoutRequest request) {
        if (request != null) {
            authService.logout(request.refreshToken());
        }
        return ApiEnvelope.success(null);
    }

    /** 当前登录用户资料（依赖 Bearer Access）。 */
    @GetMapping("/me")
    public ApiEnvelope<ApiModels.UserView> me(Authentication authentication) {
        AuthenticatedUser currentUser = CurrentUserSupport.require(authentication);
        return ApiEnvelope.success(ApiMappers.toUserView(authService.getUser(currentUser.userId())));
    }

    /** 将服务层 AuthResult 映射为对外 LoginResponse。 */
    private LoginResponse toLoginResponse(AuthService.AuthResult result) {
        return new LoginResponse(
                result.accessToken(),
                "Bearer",
                result.expiresIn(),
                result.refreshToken(),
                result.refreshExpiresIn(),
                ApiMappers.toUserView(result.user())
        );
    }
}
