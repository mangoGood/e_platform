package com.ecommerce.common.exception;

import com.ecommerce.common.result.ErrorCode;
import com.ecommerce.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p><b>核心职责（修复 A8）</b>：把 {@link BusinessException} 的业务码映射为
 * <b>真实的 HTTP 状态码</b>。此前所有异常都以 HTTP 200 返回、仅 body 中带 code，
 * 导致"无权限 403 / 未登录 401"在 HTTP 层根本无法断言，越权测试形同虚设。
 *
 * <p><b>向后兼容策略</b>：只有显式抛出 401/403/404/408/409/429/503 等状态码的
 * <b>新代码</b>会改变 HTTP 状态；存量 {@code throw new BusinessException("商品不存在")}
 * （code = 500 软失败）行为完全不变，仍返回 HTTP 200 + body.code = 500，
 * {@code tests/e2e_test.sh} 的 {@code assert_success} 断言不受影响。
 *
 * <p><b>参数校验对齐（修复 A8 遗漏项）</b>：A8 首次做状态码对齐时只覆盖了
 * {@link BusinessException}，三个参数校验分支
 * （{@link MethodArgumentNotValidException} / {@link BindException} /
 * {@link ConstraintViolationException}）仍返回裸 {@code Result}，
 * 于是 {@code body.code = 400} 而 <b>HTTP 恒为 200</b>——
 * 任何模块只要用 {@code @Valid}，参数校验在 HTTP 层就是摆设，
 * 前端响应拦截器与自动化测试全都拦不住。现已统一走
 * {@link #validationFailed(String)}，与 {@code BusinessException} 使用
 * <b>同一套白名单口径</b>，不额外开洞。
 *
 * <p><b>坏输入不再伪装成服务端故障（T03d）</b>：畸形 JSON 请求体、缺失的必需
 * 请求参数、类型不匹配的路径/查询参数、以及不被支持的 HTTP 方法，此前<b>全部</b>
 * 落到兜底的 {@link #handleException(Exception)}，对外返回
 * {@code HTTP 200 + code 500「系统异常，请联系管理员」}。这会造成三重危害：
 * 客户端用法错误被误报为服务端事故、监控与告警被 500 噪音淹没、
 * 调用方拿不到任何可修正的提示。现已为这四类异常各配一个显式分支，
 * 统一按 4xx 语义返回，并<b>只在服务端日志</b>保留框架原始细节
 * （Jackson 的解析报文常含类名、包路径与字段偏移量，回吐给客户端属于信息泄露）。
 *
 * <p><b>唯一保留 HTTP 200 的分支</b>是兜底的 {@link #handleException(Exception)}
 * （code = 500 软失败），这是 T02 定下的兼容契约，不能改。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 校验框架未给出任何可读原因时的兜底文案，避免返回空 message 的 400。 */
    private static final String DEFAULT_VALIDATION_MESSAGE = "请求参数校验失败";

    /** 请求体无法反序列化时对外的统一文案，刻意不暴露解析器内部细节。 */
    private static final String MALFORMED_BODY_MESSAGE = "请求体格式错误，无法解析";

    /** 框架未能给出名称时的占位符，避免拼出"缺少必需参数：null"这种文案。 */
    private static final String UNKNOWN_NAME = "未知";

    /** 写入日志的框架原始细节最大长度，防止超长堆栈文本刷屏。 */
    private static final int MAX_DETAIL_LENGTH = 500;

    /**
     * 需要映射为真实 HTTP 状态码的业务码白名单。
     *
     * <p>不在此集合中的业务码（典型如 500 软失败）一律保持 HTTP 200，保护存量行为。
     */
    private static final Set<Integer> HTTP_ALIGNED_CODES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    ErrorCode.BAD_REQUEST,
                    ErrorCode.UNAUTHORIZED,
                    ErrorCode.FORBIDDEN,
                    ErrorCode.NOT_FOUND,
                    ErrorCode.METHOD_NOT_ALLOWED,
                    ErrorCode.REQUEST_TIMEOUT,
                    ErrorCode.CONFLICT,
                    ErrorCode.TOO_MANY_REQUESTS,
                    ErrorCode.SERVICE_UNAVAILABLE)));

    /**
     * 处理业务异常，按错误码对齐 HTTP 状态。
     *
     * @param e 业务异常
     * @return 带真实 HTTP 状态的统一响应体
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        Integer code = e.getCode() == null ? ErrorCode.BUSINESS_FAILED : e.getCode();
        HttpStatus httpStatus = resolveHttpStatus(code);

        if (httpStatus.is5xxServerError() || httpStatus == HttpStatus.OK) {
            // 软失败与服务端错误保留完整堆栈，便于排查。
            log.error("业务异常：code={}, message={}", code, e.getMessage(), e);
        } else {
            // 4xx 属于可预期的客户端错误（未登录/越权等），打印堆栈只会污染日志。
            log.warn("业务拒绝：code={}, http={}, message={}", code, httpStatus.value(), e.getMessage());
        }
        return ResponseEntity.status(httpStatus).body(Result.error(code, e.getMessage()));
    }

    /**
     * 把业务码解析为 HTTP 状态码。
     *
     * @param code 业务错误码
     * @return 命中白名单时返回同值状态码，否则返回 {@link HttpStatus#OK}
     */
    private HttpStatus resolveHttpStatus(int code) {
        if (!HTTP_ALIGNED_CODES.contains(code)) {
            return HttpStatus.OK;
        }
        HttpStatus resolved = HttpStatus.resolve(code);
        return resolved == null ? HttpStatus.OK : resolved;
    }

    /**
     * 处理 {@code @Valid @RequestBody} 校验失败（JSON 请求体）。
     *
     * <p>注意 Spring 5.3 起 {@link MethodArgumentNotValidException} 已是
     * {@link BindException} 的子类，Spring 会优先匹配更具体的本方法，
     * 两个 handler 并存不会冲突。
     *
     * @param e 参数校验异常
     * @return HTTP 400 + 统一响应体
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException e) {
        return validationFailed(extractMessage(e));
    }

    /**
     * 处理表单/查询参数绑定校验失败。
     *
     * <p>{@code @ModelAttribute}、表单提交、以及 GET 查询参数对象绑定走的是本分支，
     * 而不是 {@link MethodArgumentNotValidException}，很容易被漏掉。
     *
     * @param e 参数绑定异常
     * @return HTTP 400 + 统一响应体
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBindException(BindException e) {
        return validationFailed(extractMessage(e));
    }

    /**
     * 处理方法级约束校验失败（类上标注 {@code @Validated} 时，
     * 直接约束在 {@code @PathVariable}/{@code @RequestParam} 上的注解）。
     *
     * <p>{@link ConstraintViolationException#getConstraintViolations()} 返回的是
     * {@code Set}，<b>迭代顺序不保证稳定</b>，同一份坏请求两次调用可能拼出顺序不同的
     * message。这里按「属性路径 → 消息」双关键字排序，保证响应可被精确断言。
     *
     * @param e 约束违反异常
     * @return HTTP 400 + 统一响应体
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolationException(
            ConstraintViolationException e) {
        Set<ConstraintViolation<?>> violations = e.getConstraintViolations();
        String message = violations == null ? "" : violations.stream()
                .sorted(Comparator
                        .<ConstraintViolation<?>, String>comparing(GlobalExceptionHandler::propertyPathOf)
                        .thenComparing(GlobalExceptionHandler::violationMessageOf))
                .map(ConstraintViolation::getMessage)
                .filter(GlobalExceptionHandler::hasText)
                .collect(Collectors.joining(", "));
        return validationFailed(message);
    }

    /**
     * 处理请求体无法反序列化（畸形 JSON、类型完全不兼容、请求体为空等）。
     *
     * <p>此前该异常落到兜底分支，客户端只能看到「系统异常，请联系管理员」+ HTTP 200，
     * 完全无法区分"我发错了"和"服务挂了"。
     *
     * <p><b>刻意不回吐 Jackson 原文</b>：{@code getMostSpecificCause()} 的消息里
     * 常包含目标类的全限定名、字段名与字符偏移量（例如
     * {@code Cannot deserialize value of type `com.ecommerce.order.entity.Address` ...}），
     * 直接返回等于免费给攻击者一份内部结构图。原文只进服务端日志。
     *
     * @param e 请求体解析异常
     * @return HTTP 400 + 脱敏后的统一响应体
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException e) {
        return clientError(ErrorCode.BAD_REQUEST, MALFORMED_BODY_MESSAGE,
                truncate(e.getMostSpecificCause().getMessage()));
    }

    /**
     * 处理必需的 {@code @RequestParam} 缺失。
     *
     * @param e 缺少请求参数异常
     * @return HTTP 400 + 指明缺了哪个参数的统一响应体
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingServletRequestParameterException(
            MissingServletRequestParameterException e) {
        String name = hasText(e.getParameterName()) ? e.getParameterName() : UNKNOWN_NAME;
        return clientError(ErrorCode.BAD_REQUEST, "缺少必需参数：" + name,
                "expectedType=" + e.getParameterType());
    }

    /**
     * 处理 {@code @PathVariable}/{@code @RequestParam} 的类型转换失败
     * （例如把 {@code /product/abc} 绑定到 {@code Long id}）。
     *
     * <p>同样只对外给出参数名，期望类型与实际收到的值写日志——实际值可能是
     * 攻击载荷，原样回显会形成反射型注入面。
     *
     * @param e 参数类型不匹配异常
     * @return HTTP 400 + 指明哪个参数类型错误的统一响应体
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException e) {
        String name = hasText(e.getName()) ? e.getName() : UNKNOWN_NAME;
        String expectedType = e.getRequiredType() == null
                ? UNKNOWN_NAME : e.getRequiredType().getSimpleName();
        return clientError(ErrorCode.BAD_REQUEST, "参数 " + name + " 类型错误",
                "expectedType=" + expectedType + ", actualValue=" + truncate(String.valueOf(e.getValue())));
    }

    /**
     * 处理 HTTP 方法不被支持（如对只接受 POST 的接口发起 GET）。
     *
     * <p>状态码走 {@link #resolveHttpStatus(int)} 统一出口而不是硬写
     * {@link HttpStatus#METHOD_NOT_ALLOWED}，保证与其余分支共用同一份白名单口径。
     *
     * <p>按 RFC 7231 §6.5.5，405 响应<b>必须</b>带 {@code Allow} 头告知可用方法，
     * 因此在统一出口之上补一个响应头，而不是另起一套响应构造逻辑。
     *
     * @param e 请求方法不支持异常
     * @return HTTP 405 + 带 {@code Allow} 头的统一响应体
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleHttpRequestMethodNotSupportedException(
            HttpRequestMethodNotSupportedException e) {
        String method = hasText(e.getMethod()) ? e.getMethod() : UNKNOWN_NAME;
        Set<HttpMethod> supported = e.getSupportedHttpMethods();
        String supportedText = supported == null || supported.isEmpty()
                ? UNKNOWN_NAME
                : supported.stream().map(HttpMethod::name).sorted().collect(Collectors.joining(","));

        ResponseEntity<Result<Void>> response = clientError(
                ErrorCode.METHOD_NOT_ALLOWED, "不支持的请求方法：" + method, "supported=" + supportedText);
        if (supported == null || supported.isEmpty()) {
            return response;
        }
        return ResponseEntity.status(response.getStatusCode())
                .allow(supported.toArray(new HttpMethod[0]))
                .body(response.getBody());
    }

    /**
     * 从绑定结果中提取可读的校验失败原因。
     *
     * <p>同时收集字段级错误与<b>全局（类级）</b>错误：只取
     * {@code getFieldErrors()} 会让类级约束（如跨字段校验）产生一条
     * <b>空消息的 400</b>，前端只能看到一个空串。
     *
     * <p><b>顺序确定化（T03d）</b>：{@code getFieldErrors()} 的顺序由校验器遍历
     * 反射得到的字段列表决定，而 {@code Class#getDeclaredFields()} 的顺序
     * <b>JVM 不保证</b>（JLS 未规定，实际会随编译器/JVM 版本变化）。同一份坏请求
     * 在不同环境上可能拼出"用户名不能为空, 密码不能为空"或反过来，
     * 自动化测试只能退化成模糊匹配。这里改为<b>按字段名字典序</b>排序
     * （同字段多条错误再按消息排序做二级定序），并让全局级错误<b>整体排在字段级之后</b>——
     * 字段级更具体、对调用方更有指导意义，应当先看到。
     * 刻意不去反射取声明顺序：那只是把不确定性换个地方藏，没有消除。
     *
     * @param e 绑定异常
     * @return 以 {@code ", "} 连接的失败原因，可能为空串
     */
    private String extractMessage(BindException e) {
        BindingResult bindingResult = e.getBindingResult();
        String fieldMessages = bindingResult.getFieldErrors().stream()
                .sorted(Comparator
                        .<FieldError, String>comparing(FieldError::getField)
                        .thenComparing(GlobalExceptionHandler::defaultMessageOf))
                .map(FieldError::getDefaultMessage)
                .filter(GlobalExceptionHandler::hasText)
                .collect(Collectors.joining(", "));
        String globalMessages = bindingResult.getGlobalErrors().stream()
                .sorted(Comparator
                        .<ObjectError, String>comparing(ObjectError::getObjectName)
                        .thenComparing(GlobalExceptionHandler::defaultMessageOf))
                .map(ObjectError::getDefaultMessage)
                .filter(GlobalExceptionHandler::hasText)
                .collect(Collectors.joining(", "));

        if (!hasText(fieldMessages)) {
            return globalMessages;
        }
        if (!hasText(globalMessages)) {
            return fieldMessages;
        }
        return fieldMessages + ", " + globalMessages;
    }

    /**
     * 参数校验失败的统一出口。
     *
     * @param message 校验失败原因，为空时使用兜底文案
     * @return HTTP 400（BAD_REQUEST 在白名单内）+ 统一响应体
     */
    private ResponseEntity<Result<Void>> validationFailed(String message) {
        return clientError(ErrorCode.BAD_REQUEST,
                hasText(message) ? message : DEFAULT_VALIDATION_MESSAGE, null);
    }

    /**
     * 客户端错误的统一出口。
     *
     * <p>刻意复用 {@link #resolveHttpStatus(int)} 而不是硬写
     * {@code HttpStatus.BAD_REQUEST} / {@code HttpStatus.METHOD_NOT_ALLOWED}：
     * 这样所有 4xx 分支与 {@link BusinessException} 共用同一份
     * {@link #HTTP_ALIGNED_CODES} 白名单口径，未来若调整白名单策略
     * 不会出现两套行为各走各的。
     *
     * <p>日志一律 {@code warn} 且<b>不打堆栈</b>：4xx 是可预期的客户端用法错误，
     * 打堆栈只会把真正的服务端故障淹没在噪音里。
     *
     * @param code           业务错误码，必须已纳入白名单才会映射为真实 HTTP 状态
     * @param clientMessage  返回给调用方的文案，已脱敏
     * @param internalDetail 仅写日志的框架原始细节，允许为 {@code null}
     * @return 带真实 HTTP 状态的统一响应体
     */
    private ResponseEntity<Result<Void>> clientError(int code, String clientMessage, String internalDetail) {
        HttpStatus httpStatus = resolveHttpStatus(code);
        if (hasText(internalDetail)) {
            log.warn("客户端请求错误：code={}, http={}, message={}, detail={}",
                    code, httpStatus.value(), clientMessage, internalDetail);
        } else {
            log.warn("客户端请求错误：code={}, http={}, message={}",
                    code, httpStatus.value(), clientMessage);
        }
        return ResponseEntity.status(httpStatus).body(Result.error(code, clientMessage));
    }

    /**
     * 判断字符串是否含有非空白内容。
     *
     * @param text 待判断字符串，允许为 {@code null}
     * @return 非 {@code null} 且去除空白后非空时返回 {@code true}
     */
    private static boolean hasText(String text) {
        return text != null && !text.trim().isEmpty();
    }

    /**
     * 取绑定错误的默认消息，{@code null} 归一为空串以便参与排序比较。
     *
     * @param error 绑定错误
     * @return 非 {@code null} 的消息文本
     */
    private static String defaultMessageOf(ObjectError error) {
        String message = error.getDefaultMessage();
        return message == null ? "" : message;
    }

    /**
     * 取约束违反的属性路径文本，{@code null} 归一为空串以便参与排序比较。
     *
     * @param violation 约束违反
     * @return 非 {@code null} 的属性路径文本
     */
    private static String propertyPathOf(ConstraintViolation<?> violation) {
        return violation.getPropertyPath() == null ? "" : violation.getPropertyPath().toString();
    }

    /**
     * 取约束违反的消息，{@code null} 归一为空串以便参与排序比较。
     *
     * @param violation 约束违反
     * @return 非 {@code null} 的消息文本
     */
    private static String violationMessageOf(ConstraintViolation<?> violation) {
        String message = violation.getMessage();
        return message == null ? "" : message;
    }

    /**
     * 截断写入日志的框架原始细节，避免超长文本刷屏。
     *
     * @param detail 原始细节，允许为 {@code null}
     * @return 截断后的文本，{@code null} 原样返回
     */
    private static String truncate(String detail) {
        if (detail == null || detail.length() <= MAX_DETAIL_LENGTH) {
            return detail;
        }
        return detail.substring(0, MAX_DETAIL_LENGTH) + "...(truncated)";
    }

    /**
     * 兜底异常处理。
     *
     * <p><b>刻意保持返回裸 {@link Result}（HTTP 200 + code 500）</b>：
     * 这是 T02 定下的向后兼容契约，存量 {@code assert_success} 类断言依赖它。
     * 不要"顺手"改成 500，会连锁破坏存量测试。
     *
     * @param e 未被其他分支捕获的异常
     * @return HTTP 200 + code = 500 的软失败响应体
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常：{}", e.getMessage(), e);
        return Result.error("系统异常，请联系管理员");
    }
}
