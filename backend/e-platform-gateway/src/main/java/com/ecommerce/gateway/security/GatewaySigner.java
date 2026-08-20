package com.ecommerce.gateway.security;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 网关信任链签名器（修复 C5：微服务裸奔）。
 *
 * <p>把「网关认定的身份」用共享密钥签名后写入下游请求头，
 * 使得任何绕过网关、直连 {@code localhost:8085~8089} 的伪造身份请求都会在下游被拒绝。
 *
 * <h3>签名规格（与 common 模块 {@code HmacUtil} 必须逐字节一致）</h3>
 * <pre>
 *   原文 = userId + "\n" + userType + "\n" + roles + "\n" + timestamp
 *   算法 = HmacSHA256(原文.getBytes(UTF_8), secret.getBytes(UTF_8))
 *   编码 = Base64 URL-safe，无填充
 * </pre>
 *
 * <p><b>匿名请求同样必须签名</b>（userId/userType 取 {@code "0"}，roles 取 {@code ""}）。
 * 否则攻击者可以用"不带签名 = 匿名"的方式绕过下游校验。
 *
 * <p><b>关于代码重复</b>：本类与 {@code com.ecommerce.common.util.HmacUtil} 存在约 15 行
 * 算法重复。这是<b>刻意为之</b> —— 网关一旦依赖 e-platform-common，
 * common 中的 {@code GatewaySignatureInterceptor} 会被自动装配进网关，
 * 把网关自己的入站请求拦下来（网关的入站请求当然没有网关签名），形成自锁。
 * 修改签名算法时务必同步修改两处。
 */
@Component
public class GatewaySigner {

    /** 签名头。 */
    public static final String HEADER_SIGN = "X-Gateway-Sign";

    /** 时间戳头（epoch 毫秒）。 */
    public static final String HEADER_TS = "X-Gateway-Ts";

    /** 用户 ID 头。 */
    public static final String HEADER_USER_ID = "X-User-Id";

    /** 用户类型头。 */
    public static final String HEADER_USER_TYPE = "X-User-Type";

    /** 角色列表头。 */
    public static final String HEADER_USER_ROLES = "X-User-Roles";

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final GatewaySecurityProperties properties;

    public GatewaySigner(GatewaySecurityProperties properties) {
        this.properties = properties;
    }

    /**
     * 向下游请求头写入身份三元组 + 时间戳 + 签名。
     *
     * <p>调用方必须保证在此之前已用 {@code HeaderSanitizer} 剥离了客户端携带的同名头，
     * 否则会出现"客户端伪造值 + 网关真实值"并存的多值头，下游取值行为不确定。
     *
     * @param headers   下游请求头（应为已清洗的干净头集合）
     * @param principal 网关认定的身份，不可为 null
     */
    public void signInto(HttpHeaders headers, AuthPrincipal principal) {
        String userId = String.valueOf(principal.getUserId());
        String userType = String.valueOf(principal.getUserType());
        String roles = principal.rolesJoined();

        // set 而非 add：确保是唯一值，杜绝多值头歧义。
        headers.set(HEADER_USER_ID, userId);
        headers.set(HEADER_USER_TYPE, userType);
        headers.set(HEADER_USER_ROLES, roles);

        if (!properties.isEnabled()) {
            return;
        }
        String timestamp = String.valueOf(System.currentTimeMillis());
        headers.set(HEADER_TS, timestamp);
        headers.set(HEADER_SIGN, sign(userId, userType, roles, timestamp));
    }

    /**
     * 计算签名。
     *
     * @param userId    用户 ID 字符串，匿名为 {@code "0"}
     * @param userType  用户类型字符串，匿名为 {@code "0"}
     * @param roles     字典序逗号连接的角色串，匿名为 {@code ""}
     * @param timestamp epoch 毫秒字符串
     * @return Base64 URL-safe 无填充签名
     */
    public String sign(String userId, String userType, String roles, String timestamp) {
        String raw = userId + "\n" + userType + "\n" + roles + "\n" + timestamp;
        return hmacBase64Url(raw);
    }

    /**
     * @param raw 签名原文
     * @return Base64 URL-safe 无填充的 HmacSHA256 结果
     * @throws IllegalStateException 密钥缺失或 JCA 异常
     */
    private String hmacBase64Url(String raw) {
        String secret = properties.getSecret();
        if (secret == null || secret.isEmpty()) {
            throw new IllegalStateException("gateway.sign.secret 未配置，无法签发网关签名");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] digest = mac.doFinal(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("网关签名计算失败: " + e.getMessage(), e);
        }
    }
}
