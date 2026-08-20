package com.ecommerce.common.result;

/**
 * 全局统一错误码常量。
 *
 * <p>约定：错误码与 HTTP 状态码的对齐规则见
 * {@code com.ecommerce.common.exception.GlobalExceptionHandler}。
 * 除 {@link #BUSINESS_FAILED}（软失败，HTTP 仍为 200，保持存量行为）外，
 * 其余错误码均会被映射为同值的真实 HTTP 状态码。
 *
 * <p>禁止在业务代码中散落魔法数字，一律引用本类常量。
 */
public final class ErrorCode {

    /** 成功。 */
    public static final int SUCCESS = 200;

    /** 已入队（抢购排队中）。 */
    public static final int ACCEPTED = 202;

    /** 参数错误 / 校验失败。 */
    public static final int BAD_REQUEST = 400;

    /** 未认证：无 Token / Token 失效 / 命中黑名单 / 网关签名校验失败。 */
    public static final int UNAUTHORIZED = 401;

    /** 无权限：权限码不足 / 归属校验失败 / 内部接口被外部访问。 */
    public static final int FORBIDDEN = 403;

    /** 资源不存在。 */
    public static final int NOT_FOUND = 404;

    /**
     * 请求方法不被支持：如对只接受 POST 的接口发起 GET。
     *
     * <p>此前该场景会落到全局兜底分支，返回 HTTP 200 + code 500「系统异常」，
     * 把一个纯粹的客户端用法错误伪装成服务端故障。
     */
    public static final int METHOD_NOT_ALLOWED = 405;

    /** 排队等待超时。 */
    public static final int REQUEST_TIMEOUT = 408;

    /** 状态冲突：重复评价 / 卖家重复回复。 */
    public static final int CONFLICT = 409;

    /** 触发限流。 */
    public static final int TOO_MANY_REQUESTS = 429;

    /** 业务软失败：HTTP 仍返回 200，仅 body.code 为 500（存量行为，不可更改）。 */
    public static final int BUSINESS_FAILED = 500;

    /** 服务不可用：队列已满 / 下游不可达。 */
    public static final int SERVICE_UNAVAILABLE = 503;

    private ErrorCode() {
        throw new AssertionError("常量类不允许实例化");
    }
}
