package com.ecommerce.gateway.controller;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import javax.crypto.SecretKey;
import javax.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class GatewayController {

    @Autowired
    private RestTemplate restTemplate;

    @Value("${service.user.url:http://localhost:8085}")
    private String userServiceUrl;

    @Value("${service.product.url:http://localhost:8086}")
    private String productServiceUrl;

    @Value("${service.order.url:http://localhost:8087}")
    private String orderServiceUrl;

    @Value("${service.mobile.url:http://localhost:8089}")
    private String mobileServiceUrl;

    @Value("${jwt.secret}")
    private String jwtSecret;

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/user/login",
            "/user/register",
            "/mobile/auth/login",
            "/mobile/auth/register"
    );

    /**
     * 仅限内部服务调用的接口路径（外部请求一律拒绝）
     * 使用精确后缀匹配，避免 contains 误伤合法路径
     */
    private static final Set<String> INTERNAL_ONLY_KEYWORDS = Set.of(
            "deduct",
            "restore"
    );

    private SecretKey getSecretKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }

    @RequestMapping(value = "/user/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxyUserRequest(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body,
            @RequestHeader HttpHeaders headers) {
        return proxyRequest(userServiceUrl, request, body, headers);
    }

    @RequestMapping(value = "/product/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxyProductRequest(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body,
            @RequestHeader HttpHeaders headers) {
        return proxyRequest(productServiceUrl, request, body, headers);
    }

    @RequestMapping(value = "/category/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxyCategoryRequest(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body,
            @RequestHeader HttpHeaders headers) {
        return proxyRequest(productServiceUrl, request, body, headers);
    }

    @RequestMapping(value = "/review/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxyReviewRequest(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body,
            @RequestHeader HttpHeaders headers) {
        return proxyRequest(productServiceUrl, request, body, headers);
    }

    @RequestMapping(value = "/order/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxyOrderRequest(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body,
            @RequestHeader HttpHeaders headers) {
        return proxyRequest(orderServiceUrl, request, body, headers);
    }

    @RequestMapping(value = "/address/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxyAddressRequest(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body,
            @RequestHeader HttpHeaders headers) {
        return proxyRequest(orderServiceUrl, request, body, headers);
    }

    @RequestMapping(value = "/mobile/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxyMobileRequest(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body,
            @RequestHeader HttpHeaders headers) {
        return proxyRequest(mobileServiceUrl, request, body, headers);
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::equals);
    }

    /**
     * 判断是否为仅限内部调用的接口（外部不可访问）
     * 检查路径最后一段是否匹配内部关键字，避免 contains 误伤合法路径
     */
    private boolean isInternalOnlyPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        // 去除尾部斜杠和查询参数
        String trimmed = path.split("\\?")[0];
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        // 检查最后一段路径是否为内部关键字
        int lastSlash = trimmed.lastIndexOf('/');
        String lastSegment = lastSlash >= 0 ? trimmed.substring(lastSlash + 1) : trimmed;
        return INTERNAL_ONLY_KEYWORDS.contains(lastSegment);
    }

    private boolean isPublicReadPath(String path, String method) {
        if (!"GET".equalsIgnoreCase(method)) {
            return false;
        }
        return path.startsWith("/product/") || path.startsWith("/product/list") ||
               path.startsWith("/category") || path.startsWith("/review/") ||
               path.startsWith("/mobile/home") || path.startsWith("/mobile/products");
    }

    private Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSecretKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private void extractUserFromToken(HttpServletRequest request, HttpHeaders proxyHeaders) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                Claims claims = parseToken(token);

                Object userIdObj = claims.get("userId");
                Object userTypeObj = claims.get("userType");

                if (userIdObj != null) {
                    proxyHeaders.set("X-User-Id", String.valueOf(userIdObj));
                }
                if (userTypeObj != null) {
                    proxyHeaders.set("X-User-Type", String.valueOf(userTypeObj));
                }
            } catch (Exception e) {
                // JWT token validation failed, skip user extraction
            }
        }
    }

    private ResponseEntity<byte[]> proxyRequest(
            String targetService,
            HttpServletRequest request,
            byte[] body,
            HttpHeaders headers) {

        String path = request.getRequestURI().substring("/api".length());

        // 拦截仅限内部调用的接口，外部请求一律拒绝
        if (isInternalOnlyPath(path)) {
            String errorJson = "{\"code\":403,\"message\":\"无权限访问该接口\",\"data\":null,\"success\":false}";
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errorJson.getBytes());
        }

        // 认证拦截：非公开路径必须携带有效JWT
        String method = request.getMethod();
        if (!isPublicPath(path) && !isPublicReadPath(path, method)) {
            String authHeader = request.getHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                String errorJson = "{\"code\":401,\"message\":\"未登录，请先登录\",\"data\":null,\"success\":false}";
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorJson.getBytes());
            }
            try {
                parseToken(authHeader.substring(7));
            } catch (Exception e) {
                String errorJson = "{\"code\":401,\"message\":\"登录已过期，请重新登录\",\"data\":null,\"success\":false}";
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorJson.getBytes());
            }
        }

        String queryString = request.getQueryString();
        String targetUrl = targetService + path + (queryString != null ? "?" + queryString : "");

        HttpHeaders proxyHeaders = new HttpHeaders();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            if (!headerName.equalsIgnoreCase("host") &&
                !headerName.equalsIgnoreCase("content-length") &&
                !headerName.equalsIgnoreCase("transfer-encoding")) {
                proxyHeaders.set(headerName, request.getHeader(headerName));
            }
        }

        extractUserFromToken(request, proxyHeaders);

        HttpMethod httpMethod = HttpMethod.valueOf(request.getMethod());
        HttpEntity<byte[]> entity = new HttpEntity<>(body, proxyHeaders);

        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(targetUrl, httpMethod, entity, byte[].class);

            HttpHeaders responseHeaders = new HttpHeaders();
            HttpHeaders originalHeaders = response.getHeaders();
            if (originalHeaders.getContentType() != null) {
                responseHeaders.setContentType(originalHeaders.getContentType());
            }

            return new ResponseEntity<>(response.getBody(), responseHeaders, response.getStatusCode());
        } catch (Exception e) {
            // 不向外暴露内部异常细节，仅返回通用错误信息
            String errorJson = "{\"code\":503,\"message\":\"服务暂时不可用，请稍后重试\",\"data\":null,\"success\":false}";
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errorJson.getBytes());
        }
    }
}
