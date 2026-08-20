package com.ecommerce.common.exception;

import com.ecommerce.common.result.ErrorCode;

/**
 * 业务异常。
 *
 * <p>抛错一律使用本异常，并优先引用 {@link ErrorCode} 常量，
 * <b>禁止 {@code return Result.error(403, ...)}</b> —— 那样 HTTP 状态仍是 200，
 * 越权测试无法按 HTTP 码断言。
 *
 * <p>错误码到 HTTP 状态的映射由
 * {@link GlobalExceptionHandler} 统一负责。
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 业务错误码，见 {@link ErrorCode}。 */
    private Integer code;

    /**
     * 构造软失败异常（code = 500，HTTP 仍为 200，保持存量行为）。
     *
     * @param message 错误文案
     */
    public BusinessException(String message) {
        super(message);
        this.code = ErrorCode.BUSINESS_FAILED;
    }

    /**
     * 构造带业务码的异常。
     *
     * @param code    业务错误码，建议引用 {@link ErrorCode} 常量
     * @param message 错误文案
     */
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code == null ? ErrorCode.BUSINESS_FAILED : code;
    }

    /**
     * 构造带业务码与根因的异常。
     *
     * @param code    业务错误码
     * @param message 错误文案
     * @param cause   根因
     */
    public BusinessException(Integer code, String message, Throwable cause) {
        super(message, cause);
        this.code = code == null ? ErrorCode.BUSINESS_FAILED : code;
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }
}
