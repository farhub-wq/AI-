package com.company.aics.config;

import com.company.aics.domain.DomainModels;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT 认证过滤器：从 Authorization Bearer 解析 Access Token 并写入 SecurityContext；无效则 401。
 * <p>
 * 仅跳过 register/login/refresh/logout 等公开认证接口；{@code /auth/me} 仍需解析 JWT。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 无需携带 Access Token 的精确路径。 */
    private static final List<String> SKIP_EXACT = List.of(
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout"
    );

    /** 文档与健康检查前缀。 */
    private static final List<String> SKIP_PREFIXES = List.of(
            "/swagger-ui",
            "/v3/api-docs",
            "/actuator/health"
    );

    private final JwtService jwtService;

    /**
     * @param jwtService 令牌解析服务
     */
    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /**
     * 公开路径不进入 JWT 校验。
     *
     * @param request 当前请求
     * @return 是否跳过本过滤器
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (SKIP_EXACT.stream().anyMatch(path::equals)) {
            return true;
        }
        return SKIP_PREFIXES.stream().anyMatch(path::startsWith);
    }

    /**
     * 异步派发不再次过滤，避免重复解析。
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    /**
     * 错误派发不再次过滤。
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    /**
     * 解析 Bearer 令牌；缺失则放行由后续鉴权处理，无效则写 401 信封。
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);

        // 无 Bearer 头：继续链路，由 Security 决定是否要求认证
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authorization.substring(7);
        try {
            // 校验签名与过期时间后建立无状态认证上下文
            AuthenticatedUser authenticatedUser = jwtService.parseAccessToken(token);
            String role = authenticatedUser.role() == null
                    ? DomainModels.UserRole.USER.name()
                    : authenticatedUser.role();
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    authenticatedUser,
                    token,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role))
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (Exception ex) {
            // 令牌无效：直接返回统一错误信封，不继续过滤器链
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("""
                    {"code":4010,"message":"invalid token","data":null}
                    """.trim());
        }
    }
}
