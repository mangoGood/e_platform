package com.ecommerce.user.controller;

import com.ecommerce.common.result.Result;
import com.ecommerce.user.dto.LoginRequest;
import com.ecommerce.user.dto.LoginResponse;
import com.ecommerce.user.dto.RefreshTokenRequest;
import com.ecommerce.user.dto.RegisterRequest;
import com.ecommerce.user.dto.TokenPair;
import com.ecommerce.user.entity.User;
import com.ecommerce.user.service.RbacService;
import com.ecommerce.user.service.TokenService;
import com.ecommerce.user.service.UserService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.Map;

/**
 * 用户中心接口。
 *
 * <h3>路由与权限（由网关 {@code RoutePermissionRegistry} 统一裁决，本类不做任何鉴权）</h3>
 * <ul>
 *   <li>{@code POST /user/register}、{@code POST /user/login} —— PUBLIC</li>
 *   <li>{@code POST /user/refresh} —— PUBLIC（access token 已过期时仍需可调用，
 *       安全性由 refresh token 自身的验签与黑名单保证）</li>
 *   <li>{@code POST /user/logout} —— AUTHENTICATED</li>
 *   <li>其余 —— 兜底 AUTHENTICATED</li>
 * </ul>
 *
 * <p>本服务同时受 {@code GatewaySignatureInterceptor} 保护：绕过网关直连 8085
 * 的请求因缺少 {@code X-Gateway-Sign} 会被拒绝为 401。
 */
@RestController
@RequestMapping("/user")
public class UserController {

    private final UserService userService;
    private final TokenService tokenService;
    private final RbacService rbacService;

    public UserController(UserService userService,
                          TokenService tokenService,
                          RbacService rbacService) {
        this.userService = userService;
        this.tokenService = tokenService;
        this.rbacService = rbacService;
    }

    /**
     * 注册。
     *
     * @param request 注册请求
     * @return 空响应
     */
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterRequest request) {
        userService.register(request);
        return Result.success();
    }

    /**
     * 登录，签发 access + refresh 双 Token。
     *
     * @param request 登录请求
     * @return 登录响应
     */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(userService.login(request));
    }

    /**
     * 用 refresh token 换取新的 access token（同时轮换 refresh token）。
     *
     * @param request 刷新请求
     * @return 新的 Token 对
     */
    @PostMapping("/refresh")
    public Result<TokenPair> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return Result.success(userService.refresh(request.getRefreshToken()));
    }

    /**
     * 登出：把当前 access token 的 jti 写入 Redis 黑名单，TTL 为其剩余有效期。
     *
     * <p>Token 从 {@code Authorization} 头读取而非请求体 —— 网关的
     * {@code HeaderSanitizer} 刻意保留了 {@code Authorization} 透传，
     * 该头能原样到达本服务。
     *
     * @param authorization {@code Bearer } 开头的授权头
     * @return 空响应
     */
    @PostMapping("/logout")
    public Result<Void> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String token = tokenService.extractBearerToken(authorization);
        userService.logout(token);
        return Result.success();
    }

    /**
     * 查询用户信息（密码哈希已抹除）。
     *
     * @param userId 用户 ID
     * @return 用户实体
     */
    @GetMapping("/info/{userId}")
    public Result<User> getUserInfo(@PathVariable Long userId) {
        return Result.success(userService.getUserById(userId));
    }

    /**
     * 查询指定用户的角色与权限，供前端做按钮级权限控制或调试鉴权问题。
     *
     * @param userId 用户 ID
     * @return 含 {@code roles} 与 {@code perms} 两个列表的对象
     */
    @GetMapping("/perms/{userId}")
    public Result<Map<String, Object>> getUserPermissions(@PathVariable Long userId) {
        Map<String, Object> data = new HashMap<>(4);
        data.put("roles", rbacService.getUserRoles(userId));
        data.put("perms", rbacService.getUserPermissions(userId));
        return Result.success(data);
    }
}
