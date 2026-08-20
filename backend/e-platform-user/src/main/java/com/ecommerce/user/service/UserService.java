package com.ecommerce.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ErrorCode;
import com.ecommerce.common.util.JwtUtil;
import com.ecommerce.user.dto.LoginRequest;
import com.ecommerce.user.dto.LoginResponse;
import com.ecommerce.user.dto.RegisterRequest;
import com.ecommerce.user.dto.TokenPair;
import com.ecommerce.user.dto.UserProfile;
import com.ecommerce.user.entity.User;
import com.ecommerce.user.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户核心业务：注册、登录、刷新、登出。
 *
 * <h3>抛错规范</h3>
 * 一律 {@code throw new BusinessException(ErrorCode.XXX, "文案")}，
 * 由 {@code GlobalExceptionHandler} 映射为真实 HTTP 状态码。
 * <b>禁止 {@code return Result.error(401, ...)}</b> —— 那样 HTTP 状态仍是 200，
 * 客户端拦截器与自动化测试都无法按状态码判定。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /** 账号状态：禁用。 */
    private static final int STATUS_DISABLED = 0;

    /** 账号状态：正常。 */
    private static final int STATUS_NORMAL = 1;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RbacService rbacService;
    private final TokenService tokenService;

    public UserService(UserMapper userMapper,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil,
                       RbacService rbacService,
                       TokenService tokenService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.rbacService = rbacService;
        this.tokenService = tokenService;
    }

    /**
     * 注册新用户，并按 {@code user_type} 绑定默认角色。
     *
     * <p>{@code user_type} 字段继续写入，<b>不做废弃</b>：存量 Web 端
     * {@code router/index.js} 的 {@code requiresSeller} 守卫、Android 两个
     * App 的身份校验都还在读它。RBAC 是在其之上叠加的一层，不是替换。
     *
     * @param request 注册请求
     * @throws BusinessException 用户名已存在时抛 409
     */
    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterRequest request) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, request.getUsername());
        User existUser = userMapper.selectOne(wrapper);
        if (existUser != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "用户名已存在");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setUserType(request.getUserType());
        user.setStatus(STATUS_NORMAL);

        userMapper.insert(user);

        // 与 user_type 等价的 RBAC 映射：1 -> ROLE_BUYER，2 -> ROLE_SELLER。
        rbacService.assignDefaultRole(user.getId(), user.getUserType());
    }

    /**
     * 登录：校验凭证并签发 access + refresh 双 Token。
     *
     * @param request 登录请求
     * @return 登录响应（含向后兼容的扁平字段与新的嵌套 user 对象）
     * @throws BusinessException 凭证错误抛 401，账号被禁用抛 403
     */
    public LoginResponse login(LoginRequest request) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, request.getUsername());
        User user = userMapper.selectOne(wrapper);

        // 用户不存在与密码错误返回完全相同的错误，避免账号枚举。
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == STATUS_DISABLED) {
            // 403 而非 401：凭证是对的，是账号被管理员关停了，重新登录也没用。
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已被禁用");
        }

        List<String> roles = resolveRoles(user);
        List<String> perms = rbacService.getUserPermissions(user.getId());

        String accessToken = jwtUtil.generateAccessToken(
                user.getId(), user.getUsername(), user.getUserType(), roles);
        String refreshToken = jwtUtil.generateRefreshToken(
                user.getId(), user.getUsername(), user.getUserType());

        log.info("用户登录成功: userId={}, username={}, roles={}",
                user.getId(), user.getUsername(), roles);

        return buildLoginResponse(user, accessToken, refreshToken, roles, perms);
    }

    /**
     * 用 refresh token 换取新的 access token。
     *
     * <h3>校验链（任一不通过即 401）</h3>
     * <ol>
     *   <li>验签 + 未过期</li>
     *   <li>{@code typ} 必须为 {@code refresh} —— 拒绝拿 access token 来刷新，
     *       否则等于让 access token 无限续期，2 小时有效期形同虚设</li>
     *   <li>jti 不在黑名单（登出后 refresh 也必须失效）</li>
     *   <li>用户仍存在且未被禁用</li>
     * </ol>
     *
     * <h3>关于轮换</h3>
     * 采用 refresh token 轮换：签发新 refresh 的同时吊销旧 refresh。
     * 只发新的却不吊销旧的等于没轮换 —— 泄露的旧 refresh 依旧可用 7 天。
     *
     * @param refreshToken 客户端持有的 refresh token
     * @return 新的 Token 对
     * @throws BusinessException 校验失败抛 401，账号被禁用抛 403
     */
    public TokenPair refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "refreshToken 不能为空");
        }
        String token = refreshToken.trim();

        Claims claims;
        try {
            claims = jwtUtil.parseToken(token);
        } catch (Exception e) {
            log.debug("refresh token 解析失败: {}", e.getMessage());
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }

        Object tokenType = claims.get(JwtUtil.CLAIM_TOKEN_TYPE);
        if (!JwtUtil.TOKEN_TYPE_REFRESH.equals(String.valueOf(tokenType))) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "无效的刷新凭证");
        }

        String jti = claims.getId();
        if (tokenService.isRevoked(jti)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }

        Long userId = readUserId(claims);
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "无效的刷新凭证");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户不存在，请重新登录");
        }
        if (user.getStatus() != null && user.getStatus() == STATUS_DISABLED) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已被禁用");
        }

        // 角色重新查库而非沿用旧 Token：提权/降权在下一次刷新时即刻生效。
        List<String> roles = resolveRoles(user);

        String newAccessToken = jwtUtil.generateAccessToken(
                user.getId(), user.getUsername(), user.getUserType(), roles);
        String newRefreshToken = jwtUtil.generateRefreshToken(
                user.getId(), user.getUsername(), user.getUserType());

        // 轮换：旧 refresh 立即作废。
        tokenService.revoke(token);

        log.info("Token 刷新成功: userId={}, roles={}", user.getId(), roles);
        return new TokenPair(newAccessToken, newRefreshToken, jwtUtil.getAccessExpirationSeconds());
    }

    /**
     * 登出：把当前 access token 加入黑名单。
     *
     * @param accessToken 当前 access token
     * @throws BusinessException Redis 不可用时抛 503（不能谎报登出成功）
     */
    public void logout(String accessToken) {
        boolean revoked = tokenService.revoke(accessToken);
        if (!revoked) {
            // Token 本就过期或无 jti，客户端视角同样是"已登出"，不必报错。
            log.debug("登出请求的 Token 已失效或不含 jti，无需写入黑名单");
        }
    }

    /**
     * 查询用户信息。
     *
     * @param userId 用户 ID
     * @return 用户实体（已抹除密码哈希）
     * @throws BusinessException 用户不存在时抛 404
     */
    public User getUserById(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        // 绝不外泄密码哈希：该接口经网关暴露给客户端，泄露 BCrypt 串等于把
        // 离线爆破的原料直接交出去。存量实现返回了完整实体，这里一并修掉。
        user.setPassword(null);
        return user;
    }

    /**
     * 按用户名查询用户。
     *
     * @param username 用户名
     * @return 用户实体；不存在时返回 null
     */
    public User getUserByUsername(String username) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        return userMapper.selectOne(wrapper);
    }

    /**
     * 解析用户角色，并对缺失映射做自愈。
     *
     * <p>正常情况下角色来自 {@code user_role} 表。但存量库里可能存在
     * migration 之后才由旧版本代码插入的用户 —— 它们有 {@code user_type} 却没有
     * {@code user_role} 记录。若直接返回空角色，网关会判定其无任何权限，
     * 表现为"能登录但干什么都 403"，且从日志里极难看出原因。
     *
     * <p>因此这里按 {@code user_type} 兜底推导角色，<b>并补写回 DB</b>，
     * 让数据自愈而不是每次登录都走兜底分支。
     *
     * @param user 用户实体
     * @return 角色码列表，永不为 null
     */
    private List<String> resolveRoles(User user) {
        List<String> roles = rbacService.getUserRoles(user.getId());
        if (roles != null && !roles.isEmpty()) {
            return roles;
        }
        log.warn("用户 {} 在 user_role 表中无角色映射，按 user_type={} 自动补齐",
                user.getId(), user.getUserType());
        String assigned = rbacService.assignDefaultRole(user.getId(), user.getUserType());
        if (assigned == null) {
            return new ArrayList<>();
        }
        List<String> repaired = rbacService.getUserRoles(user.getId());
        return repaired == null ? new ArrayList<>() : repaired;
    }

    /**
     * 组装登录响应：扁平字段与嵌套 user 对象<b>同时</b>填充。
     *
     * @param user         用户实体
     * @param accessToken  access token
     * @param refreshToken refresh token
     * @param roles        角色码列表
     * @param perms        权限码列表
     * @return 登录响应
     */
    private LoginResponse buildLoginResponse(User user, String accessToken, String refreshToken,
                                             List<String> roles, List<String> perms) {
        LoginResponse response = new LoginResponse();

        // ---- 存量扁平字段：Web 端与 Android 直接读取，缺一不可 ----
        response.setToken(accessToken);
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setUserType(user.getUserType());
        response.setAvatar(user.getAvatar());

        // ---- 新增字段 ----
        response.setRefreshToken(refreshToken);
        response.setExpiresIn(jwtUtil.getAccessExpirationSeconds());

        UserProfile profile = new UserProfile();
        profile.setId(user.getId());
        profile.setUsername(user.getUsername());
        profile.setUserType(user.getUserType());
        profile.setAvatar(user.getAvatar());
        profile.setRoles(roles);
        profile.setPerms(perms);
        response.setUser(profile);

        return response;
    }

    /**
     * 从载荷中读取 userId，兼容 JSON 反序列化出的 Integer。
     *
     * @param claims JWT 载荷
     * @return 用户 ID；缺失或非法时返回 null
     */
    private Long readUserId(Claims claims) {
        Object value = claims.get(JwtUtil.CLAIM_USER_ID);
        if (value instanceof Number) {
            long id = ((Number) value).longValue();
            return id > 0L ? id : null;
        }
        if (value instanceof String) {
            try {
                long id = Long.parseLong(((String) value).trim());
                return id > 0L ? id : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
