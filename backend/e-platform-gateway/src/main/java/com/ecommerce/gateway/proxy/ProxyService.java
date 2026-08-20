package com.ecommerce.gateway.proxy;

import com.ecommerce.gateway.queue.QueueService;
import com.ecommerce.gateway.queue.QueueVerdict;
import com.ecommerce.gateway.security.AccessDecision;
import com.ecommerce.gateway.security.AuthPrincipal;
import com.ecommerce.gateway.security.GatewaySigner;
import com.ecommerce.gateway.security.RoutePermissionRegistry;
import com.ecommerce.gateway.security.TokenResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;

/**
 * 网关转发核心：一条顺序固定、不可绕过的安全管线。
 *
 * <h3>管线顺序（严格固定，任何调整都可能开洞）</h3>
 * <ol>
 *   <li><b>INTERNAL_DENY</b> —— 命中内部专用接口（{@code /product/*&#47;deduct|restore}、
 *       {@code **&#47;internal&#47;**}）直接 403。<b>先于认证</b>，因为哪怕是管理员也不该从外网走这条路。</li>
 *   <li><b>TokenResolver</b> —— 解析 JWT + 查 Redis 黑名单。无 Token / 解析失败 → ANONYMOUS；
 *       jti 命中黑名单 → 401（已登出的凭证必须明确报错，不能静默降级为游客）。</li>
 *   <li><b>RoutePermissionRegistry</b> —— 查表决策，<b>严格区分 401 与 403</b>：
 *       没身份是 401（去登录），有身份但没权限是 403（别再试了）。</li>
 *   <li><b>QueueService</b> —— 秒杀排队守卫（T03 实现，当前为放行空实现）。</li>
 *   <li><b>HeaderSanitizer</b> —— 剥离客户端伪造的内部头（修 C4）。</li>
 *   <li><b>GatewaySigner</b> —— 写入网关认定的身份 + HMAC 签名（修 C5）。</li>
 *   <li><b>RestTemplate 转发</b> —— {@code finally} 中归还排队名额。</li>
 * </ol>
 *
 * <h3>关于下游状态码透传</h3>
 * T02 把 {@code GlobalExceptionHandler} 改成了返回真实 HTTP 401/403/404/409…，
 * 因此网关<b>必须原样透传下游状态码</b>。旧实现把所有 {@code RestTemplate} 异常
 * 一律吞成 503，会让下游的 403 在客户端看起来像"服务挂了"。
 * 这里通过「{@code RestTemplate} 装配 no-op 错误处理器 + 兜底捕获
 * {@link HttpStatusCodeException}」双保险解决。
 */
@Service
public class ProxyService {

    private static final Logger log = LoggerFactory.getLogger(ProxyService.class);

    /** 网关对外统一的 API 前缀。 */
    private static final String API_PREFIX = "/api";

    private final RestTemplate restTemplate;
    private final TokenResolver tokenResolver;
    private final RoutePermissionRegistry routeRegistry;
    private final QueueService queueService;
    private final HeaderSanitizer headerSanitizer;
    private final GatewaySigner gatewaySigner;

    public ProxyService(RestTemplate restTemplate,
                        TokenResolver tokenResolver,
                        RoutePermissionRegistry routeRegistry,
                        QueueService queueService,
                        HeaderSanitizer headerSanitizer,
                        GatewaySigner gatewaySigner) {
        this.restTemplate = restTemplate;
        this.tokenResolver = tokenResolver;
        this.routeRegistry = routeRegistry;
        this.queueService = queueService;
        this.headerSanitizer = headerSanitizer;
        this.gatewaySigner = gatewaySigner;
    }

    /**
     * 执行一次带完整安全校验的转发。
     *
     * @param targetBaseUrl 目标服务基址，如 {@code http://localhost:8086}
     * @param request       原始客户端请求
     * @param body          原始请求体，GET 等无体请求为 null
     * @return 下游响应或网关生成的错误响应，永不为 null
     */
    public ResponseEntity<byte[]> proxy(String targetBaseUrl, HttpServletRequest request, byte[] body) {
        String path = extractPath(request);
        String method = request.getMethod() == null ? "GET" : request.getMethod().toUpperCase(java.util.Locale.ROOT);

        // ---- 1. 内部专用接口：外部一律拒绝，先于一切认证 ----
        AccessDecision decision = routeRegistry.decide(path, method);
        if (decision.getType() == AccessDecision.Type.INTERNAL_DENY) {
            log.warn("拦截外部访问内部接口: {} {}", method, path);
            return error(HttpStatus.FORBIDDEN, 403, "无权限访问该接口");
        }

        // ---- 2. 解析身份 ----
        AuthPrincipal principal;
        try {
            principal = tokenResolver.resolve(request);
        } catch (TokenResolver.TokenRevokedException e) {
            return error(HttpStatus.UNAUTHORIZED, 401, e.getMessage());
        }

        // ---- 3. 权限决策：严格区分 401（没身份）与 403（没权限）----
        ResponseEntity<byte[]> denied = evaluate(decision, principal, method, path);
        if (denied != null) {
            return denied;
        }

        // ---- 4. 排队守卫 ----
        QueueVerdict verdict = queueService.acquire(path, method, principal);
        if (verdict == null) {
            verdict = QueueVerdict.PASS;
        }
        if (!verdict.isPassed()) {
            return raw(HttpStatus.valueOf(verdict.getResponseStatus()), verdict.getResponseBody());
        }

        try {
            // ---- 5. 清洗客户端头（修 C4）----
            HttpHeaders proxyHeaders = headerSanitizer.sanitize(request);

            // ---- 6. 写入可信身份 + 签名（修 C5）----
            gatewaySigner.signInto(proxyHeaders, principal);

            // ---- 7. 转发 ----
            return forward(targetBaseUrl, path, request, body, proxyHeaders, method);
        } finally {
            // 无论转发成功、失败还是抛异常，名额都必须归还。
            queueService.release(verdict);
        }
    }

    /**
     * 依据访问策略与身份做放行判定。
     *
     * @param decision  路由策略
     * @param principal 网关认定的身份
     * @param method    HTTP 方法，仅用于日志
     * @param path      请求路径，仅用于日志
     * @return 需要拒绝时返回错误响应；放行时返回 null
     */
    private ResponseEntity<byte[]> evaluate(AccessDecision decision, AuthPrincipal principal,
                                            String method, String path) {
        switch (decision.getType()) {
            case PUBLIC:
                return null;
            case AUTHENTICATED:
                if (principal.isAnonymous()) {
                    return error(HttpStatus.UNAUTHORIZED, 401, "未登录，请先登录");
                }
                return null;
            case PERMISSION:
                if (principal.isAnonymous()) {
                    return error(HttpStatus.UNAUTHORIZED, 401, "未登录，请先登录");
                }
                if (!principal.hasPermission(decision.getPermissionCode())) {
                    log.info("权限不足: userId={}, roles={}, 需要={}, 请求={} {}",
                            principal.getUserId(), principal.getRoles(),
                            decision.getPermissionCode(), method, path);
                    return error(HttpStatus.FORBIDDEN, 403, "无权限执行该操作");
                }
                return null;
            case INTERNAL_DENY:
            default:
                // INTERNAL_DENY 已在上游短路，此处仅为穷尽分支、保证默认拒绝。
                return error(HttpStatus.FORBIDDEN, 403, "无权限访问该接口");
        }
    }

    /**
     * 把请求发往下游服务并回写响应。
     *
     * @param targetBaseUrl 目标服务基址
     * @param path          已剥离 {@code /api} 前缀的路径
     * @param request       原始请求，用于取查询串
     * @param body          请求体
     * @param proxyHeaders  已清洗并签名的请求头
     * @param method        HTTP 方法
     * @return 下游响应或 503
     */
    private ResponseEntity<byte[]> forward(String targetBaseUrl, String path, HttpServletRequest request,
                                           byte[] body, HttpHeaders proxyHeaders, String method) {
        String queryString = request.getQueryString();
        String targetUrl = targetBaseUrl + path + (queryString == null ? "" : "?" + queryString);

        HttpMethod httpMethod = HttpMethod.resolve(method);
        if (httpMethod == null) {
            return error(HttpStatus.METHOD_NOT_ALLOWED, 405, "不支持的请求方法");
        }

        HttpEntity<byte[]> entity = new HttpEntity<>(body, proxyHeaders);
        try {
            ResponseEntity<byte[]> response =
                    restTemplate.exchange(targetUrl, httpMethod, entity, byte[].class);
            return new ResponseEntity<>(response.getBody(),
                    copyResponseHeaders(response.getHeaders()), response.getStatusCode());
        } catch (HttpStatusCodeException e) {
            // 兜底：即使 RestTemplate 未装配 no-op 错误处理器，下游状态码也必须原样透传，
            // 否则下游的 401/403 会被误报成 503。
            HttpHeaders headers = copyResponseHeaders(e.getResponseHeaders());
            return new ResponseEntity<>(e.getResponseBodyAsByteArray(), headers, e.getStatusCode());
        } catch (Exception e) {
            // 连接超时、下游未启动等：不向外暴露内部异常细节。
            log.error("转发失败: {} {} -> {}, 原因: {}", method, path, targetUrl, e.toString());
            return error(HttpStatus.SERVICE_UNAVAILABLE, 503, "服务暂时不可用，请稍后重试");
        }
    }

    /**
     * 精简下游响应头，只保留客户端确实需要的内容协商信息。
     *
     * <p>刻意不透传 {@code Set-Cookie}、{@code Server} 等头：既避免泄露内部实现，
     * 也避免 {@code Content-Length} / {@code Transfer-Encoding} 与网关重新计算的结果冲突。
     *
     * @param source 下游响应头，可为 null
     * @return 精简后的响应头，永不为 null
     */
    private HttpHeaders copyResponseHeaders(HttpHeaders source) {
        HttpHeaders headers = new HttpHeaders();
        if (source == null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
            return headers;
        }
        MediaType contentType = null;
        try {
            contentType = source.getContentType();
        } catch (Exception ignored) {
            // 下游返回了非法 Content-Type，退化为 JSON。
        }
        headers.setContentType(contentType == null ? MediaType.APPLICATION_JSON : contentType);
        if (source.getFirst(HttpHeaders.CONTENT_DISPOSITION) != null) {
            headers.set(HttpHeaders.CONTENT_DISPOSITION, source.getFirst(HttpHeaders.CONTENT_DISPOSITION));
        }
        return headers;
    }

    /**
     * 取出去掉上下文路径与 {@code /api} 前缀后的业务路径。
     *
     * @param request 原始请求
     * @return 以 {@code /} 开头的业务路径，如 {@code /product/add}
     */
    private String extractPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || uri.isEmpty()) {
            return "/";
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        if (uri.startsWith(API_PREFIX)) {
            uri = uri.substring(API_PREFIX.length());
        }
        return uri.isEmpty() ? "/" : uri;
    }

    /**
     * 构造网关自身产生的统一错误响应。
     *
     * @param status  HTTP 状态
     * @param code    业务错误码，与 HTTP 状态保持一致
     * @param message 面向用户的中文提示
     * @return 错误响应
     */
    private ResponseEntity<byte[]> error(HttpStatus status, int code, String message) {
        String safeMessage = escapeJson(message == null ? "请求失败" : message);
        String json = "{\"code\":" + code + ",\"message\":\"" + safeMessage
                + "\",\"data\":null,\"success\":false}";
        return raw(status, json);
    }

    /**
     * 以 JSON 形式原样返回一段响应体。
     *
     * @param status HTTP 状态
     * @param json   JSON 文本
     * @return 响应实体
     */
    private ResponseEntity<byte[]> raw(HttpStatus status, String json) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body((json == null ? "" : json).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 转义会破坏 JSON 结构的字符。错误消息目前均为常量，此处仅为防御性处理。
     *
     * @param text 原始文本
     * @return 可安全嵌入 JSON 字符串字面量的文本
     */
    private String escapeJson(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }
}
