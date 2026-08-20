package com.ecommerce.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 内部服务安全装配：为 user / product / order / mobile 四个服务注册
 * {@link GatewaySignatureInterceptor}。
 *
 * <p>被各服务的 {@code @ComponentScan(basePackages = "com.ecommerce")} 自动拾取。
 *
 * <p><b>网关（e-platform-gateway）不依赖 common 模块</b>，因此不会加载本配置，
 * 也就不会出现"网关把自己拦下来"的自锁问题。
 */
@Configuration
@EnableConfigurationProperties(GatewaySignProperties.class)
public class InternalSecurityAutoConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(InternalSecurityAutoConfig.class);

    private final GatewaySignProperties properties;

    /**
     * @param properties 签名配置（其 {@code @PostConstruct} 已完成 fail-fast 校验）
     */
    public InternalSecurityAutoConfig(GatewaySignProperties properties) {
        this.properties = properties;
        if (properties.isEnabled()) {
            log.info("网关签名校验已启用：容差窗口 {} ms", properties.getToleranceMs());
        } else {
            log.warn("网关签名校验已关闭（gateway.sign.enabled=false）——仅允许在本地调试环境使用！");
        }
    }

    /**
     * 以 {@code @Bean} 方式创建拦截器，确保其 {@code @Value} 字段能被容器注入。
     *
     * @return 网关签名校验拦截器
     */
    @Bean
    public GatewaySignatureInterceptor gatewaySignatureInterceptor() {
        return new GatewaySignatureInterceptor(properties);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 最高优先级：必须先确认"请求来源可信"，再执行任何业务侧拦截器。
        registry.addInterceptor(gatewaySignatureInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns("/actuator/**", "/error")
                .order(Ordered.HIGHEST_PRECEDENCE);
    }
}
