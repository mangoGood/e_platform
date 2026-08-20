package com.ecommerce.gateway.config;

import com.ecommerce.gateway.queue.NoopQueueService;
import com.ecommerce.gateway.queue.QueueService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;

/**
 * 网关基础装配：静态资源 / SPA 前端路由 / CORS / 转发用 RestTemplate / 排队守卫默认实现。
 *
 * <p><b>本模块刻意不依赖 {@code e-platform-common}</b>：common 中的
 * {@code GatewaySignatureInterceptor} 是自动装配的，一旦引入就会拦截网关自己的入站请求
 * （而网关的入站请求当然没有网关签名），形成自锁。
 */
@Configuration
public class GatewayConfig implements WebMvcConfigurer {

    /** 与下游服务建立连接的超时（毫秒）。下游都在本机，3 秒足够，超时说明进程没起来。 */
    private static final int CONNECT_TIMEOUT_MS = 3000;

    /** 等待下游响应的超时（毫秒）。留足秒杀高峰下的排队与库存扣减时间。 */
    private static final int READ_TIMEOUT_MS = 20000;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/");
    }

    /**
     * SPA 前端路由 forward。
     *
     * <p><b>只登记前端页面路径</b>。API 一律以 {@code /api} 开头，与这里不会冲突；
     * 排队相关的 {@code /api/queue/**} 由 {@code QueueController} 处理，
     * 前端没有 {@code /queue} 页面，因此<b>不登记 {@code /queue} 的 forward</b> ——
     * 否则将来若有人误加，会把 {@code /queue} 的轮询请求吞成 index.html。
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
        registry.addViewController("/login").setViewName("forward:/index.html");
        registry.addViewController("/register").setViewName("forward:/index.html");
        registry.addViewController("/products").setViewName("forward:/index.html");
        registry.addViewController("/product/**").setViewName("forward:/index.html");
        registry.addViewController("/cart").setViewName("forward:/index.html");
        registry.addViewController("/orders").setViewName("forward:/index.html");
        registry.addViewController("/seller/**").setViewName("forward:/index.html");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /**
     * 转发用 RestTemplate。
     *
     * <p>装配了 <b>no-op 错误处理器</b>：下游返回 4xx/5xx 时不抛异常，而是原样返回
     * {@code ResponseEntity}，由 {@code ProxyService} 透传给客户端。
     *
     * <p>这一点在 T02 之后是<b>必须的</b>：{@code GlobalExceptionHandler} 现在会返回真实的
     * HTTP 401/403/404/409，若 RestTemplate 仍按异常处理，网关会把它们统统吞成 503，
     * 前端就再也分不清"没登录"和"服务挂了"。
     *
     * @return 配置了超时与透传策略的 RestTemplate
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 关闭流式输出（output streaming）。JDK 的 HttpURLConnection 仅在收到 401/407 认证
        // 挑战时才会尝试「带凭据重发请求」，而流式模式下请求体已被写出、无法重放，于是抛
        // java.net.HttpRetryException: cannot retry due to server authentication, in streaming mode。
        // 该 I/O 异常被 ProxyService 的通用 catch (Exception) 捕获，被误判为「下游不可用」→ 网关错误返回 503
        // （典型现象：/api/user/login 密码错误时下游返回的是正确的 401，却被网关吞成 503）。
        // 关闭 streaming 后 JDK 会先把请求体全量缓冲进内存，401 挑战时即可重放，彻底消除该误报的触发条件。
        // 内存影响可忽略：已 grep 全平台确认无 MultipartFile / 文件上传端点，请求体均为小 JSON。
        factory.setOutputStreaming(false);
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);

        RestTemplate template = new RestTemplate(factory);
        template.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return false;
            }

            @Override
            public void handleError(ClientHttpResponse response) throws IOException {
                // 永不进入：hasError 恒为 false，错误状态交由 ProxyService 透传。
            }
        });
        return template;
    }

    /**
     * 排队守卫的默认实现（放行）。
     *
     * <p>T03 只要提供任意一个 {@link QueueService} Bean，本兜底 Bean 自动让位，
     * {@code ProxyService} 的调用点与 {@code finally} 归还逻辑一行都不用改。
     *
     * @return 空实现的排队守卫
     */
    @Bean
    @ConditionalOnMissingBean(QueueService.class)
    public QueueService queueService() {
        return new NoopQueueService();
    }
}
