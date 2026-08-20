package com.ecommerce.common.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collection;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 网关信任链签名工具：HmacSHA256 + Base64 URL-safe（无填充）+ 常量时间比较。
 *
 * <p><b>签名原文格式（字段顺序严格固定，{@code \n} 分隔，共 4 段）：</b>
 * <pre>
 *   userId + "\n" + userType + "\n" + roles + "\n" + timestamp
 * </pre>
 * <ul>
 *   <li>{@code userId} / {@code userType}：十进制字符串，匿名请求为 {@code "0"}</li>
 *   <li>{@code roles}：角色码字典序升序、英文逗号连接、无空格；匿名为空串 {@code ""}</li>
 *   <li>{@code timestamp}：epoch 毫秒，等于 {@code X-Gateway-Ts} 头的值</li>
 * </ul>
 *
 * <p><b>注意</b>：网关模块（e-platform-gateway）不依赖 common，其
 * {@code GatewaySigner} 持有一份等价实现。修改本类算法时必须同步修改网关侧，
 * 否则整条信任链会全线验签失败。
 */
public final class HmacUtil {

    /** JCA 算法名。 */
    private static final String HMAC_SHA256 = "HmacSHA256";

    /** 签名原文字段分隔符。 */
    private static final String FIELD_SEPARATOR = "\n";

    /** 角色码连接符。 */
    private static final String ROLE_SEPARATOR = ",";

    private HmacUtil() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 拼接签名原文。
     *
     * @param userId    用户 ID 的十进制字符串，匿名为 {@code "0"}，不可为 null
     * @param userType  用户类型的十进制字符串，匿名为 {@code "0"}，不可为 null
     * @param roles     规范化后的角色串（字典序、逗号连接），匿名为 {@code ""}，不可为 null
     * @param timestamp epoch 毫秒的字符串形式，不可为 null
     * @return 4 段以 {@code \n} 分隔的签名原文
     */
    public static String buildSignRaw(String userId, String userType, String roles, String timestamp) {
        Objects.requireNonNull(userId, "userId 不能为 null");
        Objects.requireNonNull(userType, "userType 不能为 null");
        Objects.requireNonNull(roles, "roles 不能为 null");
        Objects.requireNonNull(timestamp, "timestamp 不能为 null");
        return userId + FIELD_SEPARATOR + userType + FIELD_SEPARATOR + roles + FIELD_SEPARATOR + timestamp;
    }

    /**
     * 计算 HmacSHA256 签名并以 Base64 URL-safe（无填充）编码。
     *
     * @param secret 共享密钥，不可为空
     * @param raw    签名原文
     * @return Base64 URL-safe 无填充的签名串
     * @throws IllegalStateException 当 JCA 不支持 HmacSHA256 或密钥非法时抛出
     */
    public static String sign(String secret, String raw) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalStateException("签名密钥为空，无法计算 HMAC");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] digest = mac.doFinal(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 计算失败: " + e.getMessage(), e);
        }
    }

    /**
     * 常量时间比较两个签名串，避免时序侧信道攻击。
     *
     * <p><b>禁止改用 {@code String.equals}</b>：其短路比较会泄漏首个不同字节的位置。
     *
     * @param expected 期望签名，可为 null
     * @param actual   实际签名，可为 null
     * @return 两者非空且逐字节相等时返回 true
     */
    public static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 把角色集合规范化为签名原文所需的角色串：去重 + 去空白 + 字典序升序 + 逗号连接（无空格）。
     *
     * @param roles 角色码集合，可为 null 或空
     * @return 规范化后的角色串，输入为空时返回 {@code ""}
     */
    public static String normalizeRoles(Collection<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return "";
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String role : roles) {
            if (role != null) {
                String trimmed = role.trim();
                if (!trimmed.isEmpty()) {
                    sorted.add(trimmed);
                }
            }
        }
        return String.join(ROLE_SEPARATOR, sorted);
    }

    /**
     * 空值兜底：null 时返回默认值。
     *
     * @param value        原始值
     * @param defaultValue 默认值
     * @return value 为 null 时返回 defaultValue，否则返回 value
     */
    public static String nvl(String value, String defaultValue) {
        return value == null ? defaultValue : value;
    }
}
