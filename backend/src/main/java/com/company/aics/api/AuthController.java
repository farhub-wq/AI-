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
 * 认证 API：注册、登录签发 JWT，以及查询当前登录用户信息。
 * 注册与登录路径在 Security 中公开，无需 Bearer 令牌。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    /**
     * @param authService 认证应用服务
     */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 用户注册并返回访问令牌。
     */
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

    /**
     * 账号（邮箱或手机）登录并返回访问令牌。
     */
    @PostMapping("/login")
    public ApiEnvelope<LoginResponse> login(@Valid @RequestBody ApiModels.LoginRequest request) {
        AuthService.AuthResult result = authService.login(request.account(), request.password());
        return ApiEnvelope.success(toLoginResponse(result));
    }

    /**
     * 查询当前登录用户资料。
     */
    @GetMapping("/me")
    public ApiEnvelope<ApiModels.UserView> me(Authentication authentication) {
        AuthenticatedUser currentUser = CurrentUserSupport.require(authentication);
        return ApiEnvelope.success(ApiMappers.toUserView(authService.getUser(currentUser.userId())));
    }

    /**
     * 将应用层认证结果映射为登录响应 DTO。
     */
    private LoginResponse toLoginResponse(AuthService.AuthResult result) {
        return new LoginResponse(
                result.accessToken(),
                "Bearer",
                result.expiresIn(),
                ApiMappers.toUserView(result.user())
        );
    }
}
