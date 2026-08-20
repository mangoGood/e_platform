package com.ecommerce.mobile.config;

import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link DownstreamErrorDecoder} 的行为契约测试。
 *
 * <p>覆盖三类关注点：
 * <ol>
 *   <li><b>语义还原</b>：4xx + 标准 {@code Result} 信封要逐字还原成直连口径；</li>
 *   <li><b>降级兜底</b>：非标准 body / 空 body / 非 JSON / 流异常都要有合理结果；</li>
 *   <li><b>健壮性硬约束</b>：解码器<b>永远</b>返回异常对象，<b>永远</b>不自己抛异常。</li>
 * </ol>
 */
class DownstreamErrorDecoderTest {

    private final DownstreamErrorDecoder decoder = new DownstreamErrorDecoder(new ObjectMapper());

    private static final String METHOD_KEY = "ProductClient#getProductById(Long)";

    /**
     * 构造一个带文本 body 的下游响应。
     *
     * @param status HTTP 状态码
     * @param body   响应体，允许为 {@code null}
     * @return Feign 响应
     */
    private static Response responseOf(int status, String body) {
        Response.Builder builder = Response.builder()
                .status(status)
                .reason("test")
                .request(newRequest())
                .headers(Collections.emptyMap());
        if (body != null) {
            builder.body(body, StandardCharsets.UTF_8);
        }
        return builder.build();
    }

    /**
     * @return 一个占位请求，ErrorDecoder 不读它，仅为满足 Response 构建约束
     */
    private static Request newRequest() {
        return Request.create(Request.HttpMethod.GET,
                "http://localhost:8086/product/999999",
                Collections.emptyMap(),
                null,
                StandardCharsets.UTF_8,
                null);
    }

    /**
     * 断言解码结果是携带指定 code / message 的 {@link BusinessException}。
     *
     * @param actual          解码结果
     * @param expectedCode    期望业务码
     * @param expectedMessage 期望文案
     */
    private static void assertBusiness(Exception actual, int expectedCode, String expectedMessage) {
        assertNotNull(actual, "解码器必须返回异常对象，不能返回 null");
        BusinessException e = assertInstanceOf(BusinessException.class, actual,
                "必须抛 BusinessException，否则 GlobalExceptionHandler 无法对齐 HTTP 状态");
        assertEquals(expectedCode, e.getCode(), "业务码不符");
        assertEquals(expectedMessage, e.getMessage(), "文案不符");
    }

    // ==================== 1. 标准 Result 信封：语义逐字还原 ====================

    @Test
    @DisplayName("404 + 标准信封 -> 还原为 404「商品不存在」，与直连口径一致")
    void notFoundWithStandardEnvelope() {
        Response response = responseOf(404, "{\"code\":404,\"message\":\"商品不存在\",\"data\":null}");
        assertBusiness(decoder.decode(METHOD_KEY, response), ErrorCode.NOT_FOUND, "商品不存在");
    }

    @Test
    @DisplayName("404 + 订单信封 -> 还原为 404「订单不存在」")
    void notFoundOrder() {
        Response response = responseOf(404, "{\"code\":404,\"message\":\"订单不存在\",\"data\":null}");
        assertBusiness(decoder.decode(METHOD_KEY, response), ErrorCode.NOT_FOUND, "订单不存在");
    }

    @Test
    @DisplayName("400 + 标准信封 -> 还原为 400「订单ID不合法」")
    void badRequestWithStandardEnvelope() {
        Response response = responseOf(400, "{\"code\":400,\"message\":\"订单ID不合法\"}");
        assertBusiness(decoder.decode(METHOD_KEY, response), ErrorCode.BAD_REQUEST, "订单ID不合法");
    }

    @Test
    @DisplayName("403 + 标准信封 -> 透传 403，越权语义不丢失")
    void forbiddenIsPassedThrough() {
        Response response = responseOf(403, "{\"code\":403,\"message\":\"无权操作此订单\"}");
        assertBusiness(decoder.decode(METHOD_KEY, response), ErrorCode.FORBIDDEN, "无权操作此订单");
    }

    @Test
    @DisplayName("401 + 标准信封 -> 透传 401「用户名或密码错误」，不再伪装成系统异常")
    void unauthorizedIsPassedThrough() {
        Response response = responseOf(401, "{\"code\":401,\"message\":\"用户名或密码错误\"}");
        assertBusiness(decoder.decode(METHOD_KEY, response), ErrorCode.UNAUTHORIZED, "用户名或密码错误");
    }

    @Test
    @DisplayName("409 / 429 等其余 4xx 同样按信封透传")
    void otherClientErrorsArePassedThrough() {
        assertBusiness(decoder.decode(METHOD_KEY,
                        responseOf(409, "{\"code\":409,\"message\":\"请勿重复评价\"}")),
                ErrorCode.CONFLICT, "请勿重复评价");
        assertBusiness(decoder.decode(METHOD_KEY,
                        responseOf(429, "{\"code\":429,\"message\":\"请求过于频繁\"}")),
                ErrorCode.TOO_MANY_REQUESTS, "请求过于频繁");
    }

    // ==================== 2. 5xx：与业务软失败严格区分 ====================

    @Test
    @DisplayName("下游 500 -> 映射为 503，且不回吐下游文案（避免内部信息泄露）")
    void serverErrorMapsToServiceUnavailable() {
        Response response = responseOf(500,
                "{\"code\":500,\"message\":\"NullPointerException at com.ecommerce.product.Xxx\"}");
        Exception actual = decoder.decode(METHOD_KEY, response);
        assertBusiness(actual, ErrorCode.SERVICE_UNAVAILABLE, DownstreamErrorDecoder.UNAVAILABLE_MESSAGE);
        assertFalse(actual.getMessage().contains("NullPointerException"),
                "下游 5xx 的内部文案绝不能透给终端");
    }

    @Test
    @DisplayName("下游 502 / 503 -> 同样归一为 503")
    void badGatewayMapsToServiceUnavailable() {
        assertBusiness(decoder.decode(METHOD_KEY, responseOf(502, "<html>Bad Gateway</html>")),
                ErrorCode.SERVICE_UNAVAILABLE, DownstreamErrorDecoder.UNAVAILABLE_MESSAGE);
        assertBusiness(decoder.decode(METHOD_KEY, responseOf(503, null)),
                ErrorCode.SERVICE_UNAVAILABLE, DownstreamErrorDecoder.UNAVAILABLE_MESSAGE);
    }

    @Test
    @DisplayName("非预期的 3xx（未跟随重定向）-> 归一为 503，而非当成业务拒绝")
    void unexpectedRedirectMapsToServiceUnavailable() {
        assertBusiness(decoder.decode(METHOD_KEY, responseOf(302, null)),
                ErrorCode.SERVICE_UNAVAILABLE, DownstreamErrorDecoder.UNAVAILABLE_MESSAGE);
    }

    // ==================== 3. 非标准 / 异常 body：兜底 ====================

    @Test
    @DisplayName("Spring 默认 404 错误页（无 code 字段）-> 用 HTTP 状态兜底，且不泄露 path")
    void springDefaultErrorPageFallsBackToHttpStatus() {
        String springBody = "{\"timestamp\":\"2026-02-11T03:20:00.000+00:00\",\"status\":404,"
                + "\"error\":\"Not Found\",\"path\":\"/product/999999\"}";
        Exception actual = decoder.decode(METHOD_KEY, responseOf(404, springBody));
        assertBusiness(actual, ErrorCode.NOT_FOUND, "资源不存在");
        assertFalse(actual.getMessage().contains("/product/"), "不得把下游内部路径透给终端");
        assertFalse(actual.getMessage().contains("Not Found"), "不得直接回吐外来格式的字段");
    }

    @Test
    @DisplayName("空 body -> 用 HTTP 状态兜底")
    void emptyBodyFallsBackToHttpStatus() {
        assertBusiness(decoder.decode(METHOD_KEY, responseOf(403, "")),
                ErrorCode.FORBIDDEN, "无权限执行该操作");
    }

    @Test
    @DisplayName("无 body -> 用 HTTP 状态兜底")
    void nullBodyFallsBackToHttpStatus() {
        assertBusiness(decoder.decode(METHOD_KEY, responseOf(404, null)),
                ErrorCode.NOT_FOUND, "资源不存在");
    }

    @Test
    @DisplayName("非 JSON body（HTML 错误页）-> 用 HTTP 状态兜底，不抛解析异常")
    void nonJsonBodyFallsBackToHttpStatus() {
        assertBusiness(decoder.decode(METHOD_KEY, responseOf(400, "<html><body>Bad Request</body></html>")),
                ErrorCode.BAD_REQUEST, "请求参数不合法");
    }

    @Test
    @DisplayName("JSON 数组 body -> 用 HTTP 状态兜底")
    void jsonArrayBodyFallsBackToHttpStatus() {
        assertBusiness(decoder.decode(METHOD_KEY, responseOf(404, "[1,2,3]")),
                ErrorCode.NOT_FOUND, "资源不存在");
    }

    @Test
    @DisplayName("信封 message 为空 -> 退回状态默认文案，不返回空 message")
    void blankMessageFallsBackToDefaultText() {
        assertBusiness(decoder.decode(METHOD_KEY, responseOf(404, "{\"code\":404,\"message\":\"  \"}")),
                ErrorCode.NOT_FOUND, "资源不存在");
        assertBusiness(decoder.decode(METHOD_KEY, responseOf(404, "{\"code\":404}")),
                ErrorCode.NOT_FOUND, "资源不存在");
    }

    @Test
    @DisplayName("4xx 响应体里却写着 code 500 -> 以 HTTP 状态为准，绝不降级成 200 软失败")
    void mismatchedEnvelopeCodeNeverDowngrades4xx() {
        Response response = responseOf(404, "{\"code\":500,\"message\":\"商品不存在\"}");
        assertBusiness(decoder.decode(METHOD_KEY, response), ErrorCode.NOT_FOUND, "商品不存在");
    }

    @Test
    @DisplayName("信封 code 为 null / 非数字 -> 以 HTTP 状态为准")
    void unusableEnvelopeCodeFallsBackToHttpStatus() {
        assertBusiness(decoder.decode(METHOD_KEY, responseOf(403, "{\"code\":null,\"message\":\"拒绝\"}")),
                ErrorCode.FORBIDDEN, "无权限执行该操作");
        assertBusiness(decoder.decode(METHOD_KEY, responseOf(403, "{\"code\":\"403\",\"message\":\"拒绝\"}")),
                ErrorCode.FORBIDDEN, "无权限执行该操作");
    }

    // ==================== 4. 硬约束：解码器永不抛异常 ====================

    @Test
    @DisplayName("读 body 时抛 IOException -> 静默兜底，解码器自身不抛异常")
    void ioExceptionWhileReadingBodyIsSwallowed() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("模拟连接中断");
            }
        };
        Response response = Response.builder()
                .status(404)
                .reason("test")
                .request(newRequest())
                .headers(Collections.emptyMap())
                .body(broken, 128)
                .build();
        assertBusiness(decoder.decode(METHOD_KEY, response), ErrorCode.NOT_FOUND, "资源不存在");
    }

    @Test
    @DisplayName("response 为 null -> 兜底成 503，绝不抛 NPE")
    void nullResponseNeverThrows() {
        assertBusiness(decoder.decode(METHOD_KEY, null),
                ErrorCode.SERVICE_UNAVAILABLE, DownstreamErrorDecoder.UNAVAILABLE_MESSAGE);
    }

    @Test
    @DisplayName("ObjectMapper 为 null 时构造 -> 自建实例，功能不降级")
    void nullObjectMapperIsTolerated() {
        DownstreamErrorDecoder lenient = new DownstreamErrorDecoder(null);
        assertBusiness(lenient.decode(METHOD_KEY, responseOf(404, "{\"code\":404,\"message\":\"商品不存在\"}")),
                ErrorCode.NOT_FOUND, "商品不存在");
    }
}
