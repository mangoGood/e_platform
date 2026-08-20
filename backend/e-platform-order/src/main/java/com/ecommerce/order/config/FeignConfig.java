package com.ecommerce.order.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 出站请求装配。
 *
 * <p>order → product 的调用不经过网关，拿不到 {@code X-Gateway-Sign}，
 * 靠 {@code X-Internal-Token} 走下游的内部通道。
 *
 * <p><b>安全约束</b>：令牌<b>不再设弱口令默认值</b>。
 * 原先的 {@code ${internal.token:ePlatformInternalSecret2026}} 把一个默认密码
 * 提交进了仓库，任何人读一眼源码就能伪造内部调用。
 * 现在未配置时不挂该头，下游会明确拒绝——显式失败远好于用一个人尽皆知的密码悄悄成功。
 */
@Configuration
public class FeignConfig {

    /** 服务间直连令牌，由 {@code .env} 的 {@code INTERNAL_TOKEN} 注入，未配置时为空串。 */
    @Value("${internal.token:}")
    private String internalToken = "";

    /**
     * 为所有 Feign 出站请求追加内部令牌头。
     *
     * @return Feign 请求拦截器
     */
    @Bean
    public RequestInterceptor internalTokenRequestInterceptor() {
        return template -> {
            if (internalToken != null && !internalToken.isEmpty()) {
                template.header("X-Internal-Token", internalToken);
            }
        };
    }
}
