package com.ecommerce.mobile.config;

import com.ecommerce.mobile.MobileApplication;
import com.ecommerce.mobile.client.OrderClient;
import com.ecommerce.mobile.client.ProductClient;
import com.ecommerce.mobile.client.UserClient;
import feign.codec.ErrorDecoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>装配证据测试</b>：证明 {@link DownstreamErrorDecoder} 真的被装到了每个 Feign client 上。
 *
 * <h3>为什么需要这个测试</h3>
 * <p>Spring Cloud OpenFeign 有个经典坑：写了 {@code @Bean ErrorDecoder} 不等于它生效。
 * 配置类若<b>没</b>被组件扫描到、或被当成某个 client 的私有配置、或被
 * {@code FeignClientProperties} 的 YAML 配置覆盖，Bean 都会静默失效，
 * 代码看起来完全正确，运行时却仍走 {@code ErrorDecoder.Default}。
 * 「我写了个 Bean」不是证据，「我从真实的 client 代理里把它挖出来了」才是。
 *
 * <h3>取证路径</h3>
 * <p>直接反射进入 Feign 运行时对象图，取出<b>实际会被调用的那个</b>解码器实例：
 * <pre>
 *   ProductClient (JDK 动态代理)
 *     └─ InvocationHandler = ReflectiveFeign$FeignInvocationHandler
 *          └─ field: dispatch : Map&lt;Method, MethodHandler&gt;
 *               └─ SynchronousMethodHandler
 *                    └─ field: asyncResponseHandler : AsyncResponseHandler
 *                         └─ field: errorDecoder : ErrorDecoder   ← 断言这个
 * </pre>
 *
 * <p>同时对 {@code UserClient / ProductClient / OrderClient} 三个 client 都取证，
 * 以证明它确实是<b>全局默认配置</b>（主上下文 Bean 被三个子上下文共同继承），
 * 而不是只对某一个 client 生效。
 */
@SpringBootTest(
        classes = MobileApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // 关闭网关签名 fail-fast，测试不需要真实密钥
                "gateway.sign.enabled=false",
                // jwt.secret 在 application.yml 里是无默认值的 ${JWT_SECRET}，必须显式提供
                "jwt.secret=test-only-jwt-secret-for-assembly-verification-0123456789",
                "internal.token=test-internal-token",
                "spring.cloud.nacos.discovery.enabled=false"
        })
class FeignErrorDecoderAssemblyTest {

    @Autowired
    private ProductClient productClient;

    @Autowired
    private OrderClient orderClient;

    @Autowired
    private UserClient userClient;

    /**
     * 刻意用 {@code required = false}：Bean 缺失时上下文仍要能启动，
     * 好让下面几个反射断言真正跑起来、报出「实际装配的是 ErrorDecoder$Default」，
     * 而不是在依赖注入阶段就整体炸掉——那样负向对照只能证明「Bean 在不在」，
     * 证明不了「反射断言抓不抓得住装配失败」。
     */
    @Autowired(required = false)
    private ErrorDecoder errorDecoderBean;

    @Test
    @DisplayName("容器里的 ErrorDecoder Bean 就是 DownstreamErrorDecoder")
    void errorDecoderBeanIsRegistered() {
        assertNotNull(errorDecoderBean, "主上下文必须存在 ErrorDecoder Bean");
        assertInstanceOf(DownstreamErrorDecoder.class, errorDecoderBean,
                "主上下文必须存在唯一的 DownstreamErrorDecoder Bean");
    }

    @Test
    @DisplayName("ProductClient 代理内部实际持有的 ErrorDecoder 是 DownstreamErrorDecoder")
    void productClientCarriesTheDecoder() {
        assertDecoderAssembled(productClient, "ProductClient");
    }

    @Test
    @DisplayName("OrderClient 代理内部实际持有的 ErrorDecoder 是 DownstreamErrorDecoder")
    void orderClientCarriesTheDecoder() {
        assertDecoderAssembled(orderClient, "OrderClient");
    }

    @Test
    @DisplayName("UserClient 代理内部实际持有的 ErrorDecoder 是 DownstreamErrorDecoder")
    void userClientCarriesTheDecoder() {
        assertDecoderAssembled(userClient, "UserClient");
    }

    @Test
    @DisplayName("三个 client 共享同一个解码器实例，证明其为全局默认配置而非 per-client 配置")
    void allClientsShareTheSameDecoderInstance() {
        ErrorDecoder fromProduct = extractErrorDecoder(productClient);
        ErrorDecoder fromOrder = extractErrorDecoder(orderClient);
        ErrorDecoder fromUser = extractErrorDecoder(userClient);
        assertSame(fromProduct, fromOrder, "product 与 order 应共享主上下文的同一个 Bean 实例");
        assertSame(fromProduct, fromUser, "product 与 user 应共享主上下文的同一个 Bean 实例");
        assertSame(errorDecoderBean, fromProduct, "client 内部持有的应就是容器里那个 Bean");
    }

    @Test
    @DisplayName("dismiss404 保持关闭：404 必须进入解码器，而不是被吞成 null")
    void dismiss404IsDisabled() throws Exception {
        Object methodHandler = firstMethodHandler(productClient);
        Object responseHandler = readField(methodHandler, "asyncResponseHandler");
        Object dismiss404 = readField(responseHandler, "dismiss404");
        assertInstanceOf(Boolean.class, dismiss404);
        assertFalse((Boolean) dismiss404,
                "dismiss404 若为 true，404 会被静默转成 null 返回值，解码器根本不会被调用");
    }

    /**
     * 断言指定 Feign client 代理内部装配的解码器类型正确。
     *
     * @param client     Feign client 代理对象
     * @param clientName 用于失败信息的可读名称
     */
    private static void assertDecoderAssembled(Object client, String clientName) {
        ErrorDecoder decoder = extractErrorDecoder(client);
        assertNotNull(decoder, clientName + " 内部未持有任何 ErrorDecoder");
        assertInstanceOf(DownstreamErrorDecoder.class, decoder,
                clientName + " 实际装配的解码器是 " + decoder.getClass().getName()
                        + "，说明自定义 ErrorDecoder 未生效（多半仍是 ErrorDecoder.Default）");
    }

    /**
     * 从 Feign client 代理中反射取出实际生效的 {@link ErrorDecoder}。
     *
     * @param client Feign client 代理对象
     * @return 实际生效的解码器
     */
    private static ErrorDecoder extractErrorDecoder(Object client) {
        try {
            Object methodHandler = firstMethodHandler(client);
            Object responseHandler = readField(methodHandler, "asyncResponseHandler");
            return (ErrorDecoder) readField(responseHandler, "errorDecoder");
        } catch (Exception e) {
            throw new AssertionError("无法从 Feign 代理中提取 ErrorDecoder："
                    + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * 取出代理 dispatch 表里的任意一个 {@code SynchronousMethodHandler}。
     *
     * <p>Feign 为接口的每个方法各建一个 MethodHandler，但它们共享同一份
     * {@code AsyncResponseHandler}，取任意一个即可代表该 client 的装配结果。
     *
     * @param client Feign client 代理对象
     * @return 方法处理器
     * @throws Exception 反射失败
     */
    @SuppressWarnings("unchecked")
    private static Object firstMethodHandler(Object client) throws Exception {
        assertTrue(Proxy.isProxyClass(client.getClass()),
                "Feign client 应当是 JDK 动态代理，实际是 " + client.getClass().getName());
        InvocationHandler handler = Proxy.getInvocationHandler(client);
        Object dispatch = readField(handler, "dispatch");
        Map<Method, Object> map = (Map<Method, Object>) dispatch;
        assertNotNull(map, "Feign 代理的 dispatch 表为空");
        assertFalse(map.isEmpty(), "Feign 代理的 dispatch 表没有任何方法");
        return map.values().iterator().next();
    }

    /**
     * 读取对象的私有字段，沿继承链向上查找。
     *
     * @param target    目标对象
     * @param fieldName 字段名
     * @return 字段值
     * @throws Exception 字段不存在或不可读
     */
    private static Object readField(Object target, String fieldName) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException("在 " + target.getClass().getName()
                + " 及其父类上找不到字段 " + fieldName);
    }
}
