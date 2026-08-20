package com.ecommerce.product.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 出站请求装配（写法与 {@code e-platform-order} 的同名类保持一致）。
 *
 * <p>product → order / user 的调用<b>不经过网关</b>，因此拿不到 {@code X-Gateway-Sign}。
 * 下游的 {@code GatewaySignatureInterceptor} 对这类链路开了一条独立通道：
 * 携带正确的 {@code X-Internal-Token} 即放行。本拦截器负责把该令牌统一挂到所有出站请求上。
 *
 * <p><b>安全约束</b>：令牌<b>不设弱口令默认值</b>。未配置时出站请求不会带该头，
 * 下游会以 401 拒绝——这比"悄悄用一个提交进仓库的默认密码"安全得多。
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
