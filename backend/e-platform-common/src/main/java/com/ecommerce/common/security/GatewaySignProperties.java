package com.ecommerce.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.annotation.PostConstruct;

/**
 * 网关签名校验配置（配置前缀 {@code gateway.sign}）。
 *
 * <p>提供 <b>fail-fast</b> 校验：当 {@link #enabled} 为 true 而 {@link #secret}
 * 缺失或长度不足时，服务在启动阶段即抛出异常退出，而不是带着一个"看似开启、
 * 实则形同虚设"的安全开关继续对外提供服务。
 */
@ConfigurationProperties(prefix = "gateway.sign")
public class GatewaySignProperties {

    /** 密钥最小长度（字符数）。低于此长度的 HMAC 密钥不具备足够熵。 */
    public static final int MIN_SECRET_LENGTH = 32;

    /** 默认时间窗口：5 分钟。 */
    private static final long DEFAULT_TOLERANCE_MS = 300_000L;

    /** 是否开启签名校验。本地裸调服务调试时可置 false。 */
    private boolean enabled = true;

    /** 共享密钥，来源于环境变量 {@code GATEWAY_SIGN_SECRET}。 */
    private String secret = "";

    /** 允许的时间偏移窗口（毫秒），|now - ts| 超过该值即判定过期。 */
    private long toleranceMs = DEFAULT_TOLERANCE_MS;

    /**
     * 启动期硬校验：开关打开时密钥必须存在且足够长。
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
        if (toleranceMs <= 0L) {
            throw new IllegalStateException(buildErrorMessage(
                    "gateway.sign.tolerance-ms 必须为正数（当前 " + toleranceMs + "）",
                    "非正数时间窗会导致所有请求被判定为过期"));
        }
    }

    /**
     * 构造一条人类可读、可直接照做的启动失败说明。
     *
     * @param problem 问题描述
     * @param reason  根因说明
     * @return 多行错误信息
     */
    private String buildErrorMessage(String problem, String reason) {
        return String.join(System.lineSeparator(),
                "",
                "============================================================",
                " 启动失败：网关签名校验配置不合法",
                "============================================================",
                " 问题 : " + problem,
                " 原因 : " + reason,
                "------------------------------------------------------------",
                " 修复方式（任选其一）：",
                "   1) 使用 ./start.sh 启动（会自动生成 .env 并注入 GATEWAY_SIGN_SECRET）",
                "   2) 手动导出足够长的密钥后再启动：",
                "        export GATEWAY_SIGN_SECRET=\"$(openssl rand -base64 32)\"",
                "   3) 仅本地裸调服务调试时，显式关闭校验（严禁用于线上）：",
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
