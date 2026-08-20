package com.ecommerce.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 网关签名签发配置（配置前缀 {@code gateway.sign}）。
 *
 * <p>与 common 模块的 {@code GatewaySignProperties} 读取同一份配置、共享同一个密钥：
 * 网关用它<b>签发</b>，各内部服务用它<b>验签</b>。
 *
 * <p>网关模块不依赖 e-platform-common（否则 common 中的签名校验拦截器会把网关自己拦下来），
 * 因此这里保留一份独立实现。
 *
 * <p>同样提供 fail-fast：开关打开而密钥缺失/过短时，网关启动即失败 ——
 * 否则网关会签发一批下游根本验不过的请求，表现为全站 401，排查成本极高。
 */
@Component
@ConfigurationProperties(prefix = "gateway.sign")
public class GatewaySecurityProperties {

    /** 密钥最小长度（字符数）。 */
    public static final int MIN_SECRET_LENGTH = 32;

    /** 默认时间窗口：5 分钟。 */
    private static final long DEFAULT_TOLERANCE_MS = 300_000L;

    /** 是否签发签名头。关闭后下游服务也必须同步关闭校验。 */
    private boolean enabled = true;

    /** 共享密钥，来源于环境变量 {@code GATEWAY_SIGN_SECRET}。 */
    private String secret = "";

    /** 时间窗口（毫秒），网关侧仅用于日志与自检，实际判定在下游。 */
    private long toleranceMs = DEFAULT_TOLERANCE_MS;

    /**
     * 启动期硬校验。
     *
     * @throws IllegalStateException 密钥缺失或过短
     */
    @PostConstruct
    public void validate() {
        if (!enabled) {
            return;
        }
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException(buildErrorMessage(
                    "gateway.sign.secret 为空",
                    "未设置环境变量 GATEWAY_SIGN_SECRET，或 .env 未被加载"));
        }
        if (secret.trim().length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(buildErrorMessage(
                    "gateway.sign.secret 长度不足（当前 " + secret.trim().length()
                            + " 字符，要求 >= " + MIN_SECRET_LENGTH + " 字符）",
                    "密钥熵不足，无法抵抗离线暴力破解"));
        }
    }

    private String buildErrorMessage(String problem, String reason) {
        return String.join(System.lineSeparator(),
                "",
                "============================================================",
                " 启动失败：网关签名签发配置不合法",
                "============================================================",
                " 问题 : " + problem,
                " 原因 : " + reason,
                "------------------------------------------------------------",
                " 影响 : 网关将签发下游无法校验的请求，表现为全站 401",
                "------------------------------------------------------------",
                " 修复方式（任选其一）：",
                "   1) 使用 ./start.sh 启动（会自动生成 .env 并注入 GATEWAY_SIGN_SECRET）",
                "   2) 手动导出足够长的密钥后再启动：",
                "        export GATEWAY_SIGN_SECRET=\"$(openssl rand -base64 32)\"",
                "   3) 仅本地调试时，网关与全部下游服务同时关闭校验（严禁用于线上）：",
                "        export GATEWAY_SIGN_ENABLED=false",
                "============================================================",
                "");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getToleranceMs() {
        return toleranceMs;
    }

    public void setToleranceMs(long toleranceMs) {
        this.toleranceMs = toleranceMs;
    }
}
