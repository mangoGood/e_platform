package com.ecommerce.gateway.controller;

import com.ecommerce.gateway.queue.QueueJson;
import com.ecommerce.gateway.queue.QueuePollResult;
import com.ecommerce.gateway.queue.RedisQueueService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 排队状态轮询接口。
 *
 * <p>被 202 拦下的客户端拿着 {@code queueToken} 轮询本接口，直到 {@code ready=true}，
 * 然后带 {@code X-Queue-Token} 头重发原请求。
 *
 * <h3>本接口<b>刻意不做鉴权</b> —— 这是决策，不是遗漏</h3>
 * {@code /api/queue/**} 不经过 {@code GatewayController} 与 {@code ProxyService}，
 * 因此整条安全管线（TokenResolver / RoutePermissionRegistry / GatewaySigner）都不生效。
 * 之所以可以接受：
 * <ul>
 *   <li>票据是服务端生成的 <b>UUIDv4</b>，122 位随机熵，不可枚举、不可猜测；</li>
 *   <li>轮询是<b>纯查询</b>语义，唯一副作用是把自己的票据晋升为名额 ——
 *       而拿到名额本身不构成任何越权：真正的下单请求仍要重新走完整管线，
 *       在那里被 {@code order:create} 权限拦截。<b>票据不是身份凭证</b>；</li>
 *   <li>响应体<b>不包含任何用户身份信息</b>（无 userId / username / roles），
 *       所以即便票据泄露，攻击者也只能看到一个位次数字。</li>
 * </ul>
 * <p><b>维护约束</b>：后续任何人给本接口增加返回字段前，请先确认它不含用户身份数据；
 * 若确有必要回显身份，必须先把接口挂到鉴权管线上，而不是直接加字段。
 *
 * <h3>路由不会被吞掉的两个前提（已核对）</h3>
 * <ol>
 *   <li>{@code GatewayController} 只映射 user/product/category/review/comment/order/address/mobile，
 *       没有 {@code /api/queue/**}，因此不会被代理转发到下游；</li>
 *   <li>{@code GatewayConfig#addViewControllers} 没有登记 {@code /queue} 的 SPA forward，
 *       因此不会被 forward 成 index.html。{@code addResourceHandlers} 虽然注册了 {@code /**}，
 *       但 {@code SimpleUrlHandlerMapping} 的 order 低于 {@code RequestMappingHandlerMapping}，
 *       本 Controller 优先命中。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/queue")
public class QueueController {

    /**
     * {@code queue.enabled=false} 时 {@link RedisQueueService} 不会注册，
     * 用 ObjectProvider 允许其缺失，而不是让整个 Controller 启动失败。
     */
    private final ObjectProvider<RedisQueueService> queueServiceProvider;

    public QueueController(ObjectProvider<RedisQueueService> queueServiceProvider) {
        this.queueServiceProvider = queueServiceProvider;
    }

    /**
     * 查询排队进度，必要时晋升为名额。
     *
     * <p>返回三态：
     * <ul>
     *   <li>200 {@code ready=true} —— 可以带 {@code X-Queue-Token} 重发原请求；</li>
     *   <li>202 {@code ready=false} —— 继续按 {@code pollInterval} 轮询；</li>
     *   <li>408 —— 票据失效或等待超时，客户端应<b>丢弃票据、重新提交原请求</b>
     *       （而不是继续轮询一个已经不存在的票据）。</li>
     * </ul>
     *
     * @param token 排队票据，来自 202 响应体的 {@code data.queueToken}
     * @return 统一格式的 JSON 响应
     */
    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> status(@RequestParam(name = "token", required = false) String token) {
        RedisQueueService queueService = queueServiceProvider.getIfAvailable();
        if (queueService == null) {
            // 排队功能已关闭：没有队列可排，直接告诉客户端继续。
            // 返回 408 会让客户端误以为超时而重试，返回 ready 才是语义正确的。
            return json(HttpStatus.OK, QueueJson.statusReadyBody(token == null ? "" : token));
        }

        QueuePollResult result = queueService.poll(token);
        if (result.isExpired()) {
            return json(HttpStatus.REQUEST_TIMEOUT, QueueJson.statusTimeoutBody());
        }
        if (result.isReady()) {
            return json(HttpStatus.OK, QueueJson.statusReadyBody(result.getToken()));
        }
        return json(HttpStatus.ACCEPTED, QueueJson.statusWaitingBody(
                result.getToken(), result.getPosition(),
                result.getEstimatedWaitSeconds(), result.getPollInterval()));
    }

    /**
     * 包装 JSON 响应，统一设置 Content-Type。
     *
     * @param status HTTP 状态
     * @param body   JSON 文本
     * @return 响应实体
     */
    private ResponseEntity<String> json(HttpStatus status, String body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
