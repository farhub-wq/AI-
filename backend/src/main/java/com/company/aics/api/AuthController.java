package com.company.aics.api;

import com.company.aics.api.ApiModels.LoginResponse;
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
 * 认证 API：注册/登录签发 Access+Refresh，刷新轮换，登出吊销。
 * <p>
 * register/login/refresh/logout 在 Security 中公开；{@code /me} 需有效 Access Bearer。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    /** @param authService 认证应用服务 */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 用户注册并返回 Access + Refresh。 */
    @PostMapping("/register")
    public ApiEnvelope<LoginResponse> register(@Valid @RequestBody ApiModels.RegisterRequest request) {
        AuthService.AuthResult result = authService.register(
                request.email(),
                request.phone(),
                request.password(),
                request.displayName()
        );
        return ApiEnvelope.success(toLoginResponse(result));
    }

    /** 账号（邮箱或手机）登录并返回令牌对。 */
    @PostMapping("/login")
    public ApiEnvelope<LoginResponse> login(@Valid @RequestBody ApiModels.LoginRequest request) {
        AuthService.AuthResult result = authService.login(request.account(), request.password());
        return ApiEnvelope.success(toLoginResponse(result));
    }

    /** 使用 Refresh 换取新的令牌对（旧 Refresh 作废）。 */
    @PostMapping("/refresh")
    public ApiEnvelope<LoginResponse> refresh(@Valid @RequestBody ApiModels.RefreshTokenRequest request) {
        return ApiEnvelope.success(toLoginResponse(authService.refresh(request.refreshToken())));
    }

    /** 吊销 Refresh Token（body 可空，便于前端兜底清理）。 */
    @PostMapping("/logout")
    public ApiEnvelope<Void> logout(@RequestBody(required = false) ApiModels.LogoutRequest request) {
        if (request != null) {
            authService.logout(request.refreshToken());
        }
        return ApiEnvelope.success(null);
    }

    /** 查询当前登录用户资料（需 Access Token）。 */
    @GetMapping("/me")
    public ApiEnvelope<ApiModels.UserView> me(Authentication authentication) {
        AuthenticatedUser currentUser = CurrentUserSupport.require(authentication);
        return ApiEnvelope.success(ApiMappers.toUserView(authService.getUser(currentUser.userId())));
    }

    /** 将应用层结果映射为对外登录响应 DTO。 */
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
