package com.ecommerce.mobile.exception;

import com.ecommerce.common.result.ErrorCode;
import com.ecommerce.common.result.Result;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Feign <b>传输层</b>故障的兜底处理器。
 *
 * <h3>它补的是 {@code DownstreamErrorDecoder} 覆盖不到的那一半</h3>
 * <p>{@link com.ecommerce.mobile.config.DownstreamErrorDecoder} 只在下游
 * <b>确实返回了一个 HTTP 响应</b>（非 2xx）时才会被调用。若下游<b>压根没应答</b>——
 * 进程挂了、端口不通、连接超时、读超时——Feign 抛的是
 * {@code RetryableException}（{@code FeignException} 子类），
 * 根本不经过 {@code ErrorDecoder}。同理，2xx 响应体反序列化失败抛的
 * {@code DecodeException} 也是 {@code FeignException} 子类。
 *
 * <p>这两类异常若无人认领，会落到 {@code GlobalExceptionHandler} 的兜底分支，
 * 又变回 {@code HTTP 200 + code 500「系统异常，请联系管理员」}——
 * 也就是本轮要消灭的那个症状，只是触发条件从「下游返回 4xx」换成了「下游没返回」。
 * 不补这一刀，缺陷只修了一半。
 *
 * <h3>为什么统一映射为 503 而不是 500</h3>
 * <p>「BFF 自己好好的，是依赖不可用」在 HTTP 语义里就是
 * {@link HttpStatus#SERVICE_UNAVAILABLE}。{@link ErrorCode#SERVICE_UNAVAILABLE}
 * 的定义本就是「服务不可用：队列已满 / 下游不可达」，且已在
 * {@code GlobalExceptionHandler} 的白名单内，能落地成真实 HTTP 503。
 * 用 500 则会与「BFF 自身代码 bug」混淆，且 code 500 在白名单外，
 * HTTP 会退回 200，问题原样复发。
 *
 * <h3>为什么不会和 GlobalExceptionHandler 打架</h3>
 * <p>{@code @Order(HIGHEST_PRECEDENCE)} 保证本 advice 排在
 * 无 {@code @Order} 标注（即 {@code LOWEST_PRECEDENCE}）的
 * {@code GlobalExceptionHandler} 之前。本类<b>只</b>声明了
 * {@code FeignException} 一个 handler，其余异常在本 advice 内匹配不到方法，
 * 会继续下沉到 {@code GlobalExceptionHandler}，存量行为不受影响。
 *
 * <p><b>刻意不回吐 {@code e.getMessage()}</b>：Feign 的传输层异常消息里含
 * 下游主机名、端口与完整 URL（如 {@code connect timed out executing GET
 * http://localhost:8086/product/1}），回吐等于把内网拓扑送给外部调用方。
 * 原文只进服务端日志。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class FeignTransportExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(FeignTransportExceptionHandler.class);

    /** 对外固定文案，不含任何内网拓扑信息。 */
    private static final String UNAVAILABLE_MESSAGE = "服务暂时不可用，请稍后重试";

    /**
     * 处理下游不可达 / 超时 / 响应体解码失败。
     *
     * @param e Feign 传输层异常
     * @return HTTP 503 + 脱敏后的统一响应体
     */
    @ExceptionHandler(FeignException.class)
    public ResponseEntity<Result<Void>> handleFeignException(FeignException e) {
        log.error("下游服务调用失败（传输层）：status={}, message={}", e.status(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Result.error(ErrorCode.SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE));
    }
}
