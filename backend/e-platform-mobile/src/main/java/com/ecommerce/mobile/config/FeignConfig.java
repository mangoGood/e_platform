package com.ecommerce.mobile.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.RequestInterceptor;
import feign.codec.ErrorDecoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * mobile BFF 的 Feign 全局装配。
 *
 * <h3>装配范围：全局默认（有意为之）</h3>
 * <p>本类带 {@code @Configuration} 且位于 {@code com.ecommerce.mobile.config}，
 * 会被 {@code MobileApplication} 上的 {@code @ComponentScan("com.ecommerce")} 扫到，
 * 因此它的 Bean 落在<b>主应用上下文</b>里，而不是某个 Feign client 的子上下文。
 *
 * <p>Spring Cloud OpenFeign 在构建每个 client 时会执行
 * {@code FeignClientFactoryBean.getInheritedAwareOptional(ctx, ErrorDecoder.class)}
 * →（{@code inheritParentContext} 默认 true）→ {@code FeignContext.getInstance(...)}
 * → 子上下文 {@code getBean(ErrorDecoder.class)} →
 * 子上下文没有则<b>回退到父上下文</b>。而 {@code FeignClientsConfiguration}
 * 默认<b>不</b>定义 {@code ErrorDecoder} Bean，不存在覆盖冲突。
 * 于是这里的 Bean 对 {@code UserClient / ProductClient / OrderClient}
 * <b>三个 client 全部生效</b>——这正是我们要的：三条下游链路的错误语义必须一致。
 *
 * <p>反过来说，如果<b>只想给某一个 client</b> 定制配置，那个配置类
 * <b>就不能带 {@code @Configuration}</b>（否则会污染全局默认），
 * 而应作为 {@code @FeignClient(configuration = Xxx.class)} 的参数传入。
 * 本项目三个 {@code @FeignClient} 都<b>没有</b>声明 {@code configuration}，
 * 走的就是「主上下文全局默认」这条路径。
 */
@Configuration
public class FeignConfig {

    /** 服务间直连内部令牌，来自 {@code internal.token} 配置。 */
    @Value("${internal.token:}")
    private String internalToken;

    /**
     * Feign 请求拦截器：为 mobile BFF 直连 user / product / order 的调用附加
     * {@code X-Internal-Token}。
     *
     * <p>mobile BFF 的下游调用不经过网关，因此下游服务（已启用
     * {@code GatewaySignatureInterceptor}）既收不到网关签名，也不会被网关的
     * {@code HeaderSanitizer} 清洗。要让这些直连调用被放行，必须携带与下游
     * {@code internal.token} 一致的内部令牌（与网关签名二选一的信任路径）。
     *
     * @return 在每个 Feign 出向请求头写入 {@code X-Internal-Token}
     */
    @Bean
    public RequestInterceptor internalTokenRequestInterceptor() {
        return template -> template.header("X-Internal-Token", internalToken);
    }

    /**
     * 下游错误解码器：把下游 4xx/5xx 翻译回 {@link com.ecommerce.common.exception.BusinessException}。
     *
     * <p>不配置它时 Feign 走 {@code ErrorDecoder.Default}，非 2xx 一律抛
     * {@code FeignException}，最终被兜底成「系统异常，请联系管理员」——
     * 详见 {@link DownstreamErrorDecoder} 的类注释。
     *
     * @param objectMapper Spring Boot 自动装配的 Jackson 实例
     * @return 下游错误解码器
     */
    @Bean
    public ErrorDecoder downstreamErrorDecoder(ObjectMapper objectMapper) {
        return new DownstreamErrorDecoder(objectMapper);
    }
}
