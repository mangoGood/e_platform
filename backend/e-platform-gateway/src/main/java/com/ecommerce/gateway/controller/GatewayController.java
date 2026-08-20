package com.ecommerce.gateway.controller;

import com.ecommerce.gateway.proxy.ProxyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

/**
 * 网关路由壳。
 *
 * <p>本类<b>只做一件事</b>：把 {@code /api/xxx} 映射到目标服务基址，然后交给
 * {@link ProxyService} 走安全管线。所有鉴权、Header 清洗、签名、排队逻辑都在
 * {@code security} / {@code proxy} / {@code queue} 包中，
 * <b>Controller 里不允许出现任何一行安全判断</b>。
 *
 * <p>这样做的直接收益：新增一个下游路由时不可能"忘记加鉴权"——
 * 安全策略由 {@code RoutePermissionRegistry} 的兜底规则（默认 {@code AUTHENTICATED}）统一覆盖。
 *
 * <p><b>注意</b>：{@code /api/queue/**} 不在此处路由。排队接口由网关自身的
 * {@code QueueController}（T03）本地处理，不转发给任何下游服务。
 */
@RestController
@RequestMapping("/api")
public class GatewayController {

    private final ProxyService proxyService;

    @Value("${service.user.url:http://localhost:8085}")
    private String userServiceUrl;

    @Value("${service.product.url:http://localhost:8086}")
    private String productServiceUrl;

    @Value("${service.order.url:http://localhost:8087}")
    private String orderServiceUrl;

    @Value("${service.mobile.url:http://localhost:8089}")
    private String mobileServiceUrl;

    public GatewayController(ProxyService proxyService) {
        this.proxyService = proxyService;
    }

    /**
     * 用户中心路由：登录、注册、刷新、登出、用户信息、权限查询。
     *
     * @param request 原始请求
     * @param body    原始请求体，可为 null
     * @return 下游响应
     */
    @RequestMapping(value = "/user/**", method = {RequestMethod.GET, RequestMethod.POST,
            RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxyUserRequest(HttpServletRequest request,
                                                   @RequestBody(required = false) byte[] body) {
        return proxyService.proxy(userServiceUrl, request, body);
    }

    /**
     * 商品路由。
     *
     * @param request 原始请求
     * @param body    原始请求体，可为 null
     * @return 下游响应
     */
    @RequestMapping(value = "/product/**", method = {RequestMethod.GET, RequestMethod.POST,
            RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxyProductRequest(HttpServletRequest request,
                                                      @RequestBody(required = false) byte[] body) {
        return proxyService.proxy(productServiceUrl, request, body);
    }

    /**
     * 分类路由（归属 product 服务）。
     *
     * @param request 原始请求
     * @param body    原始请求体，可为 null
     * @return 下游响应
     */
    @RequestMapping(value = "/category/**", method = {RequestMethod.GET, RequestMethod.POST,
            RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxyCategoryRequest(HttpServletRequest request,
                                                       @RequestBody(required = false) byte[] body) {
        return proxyService.proxy(productServiceUrl, request, body);
    }

    /**
     * 旧版评价路由（兼容层，归属 product 服务）。
     *
     * @param request 原始请求
     * @param body    原始请求体，可为 null
     * @return 下游响应
     */
    @RequestMapping(value = "/review/**", method = {RequestMethod.GET, RequestMethod.POST,
            RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxyReviewRequest(HttpServletRequest request,
                                                     @RequestBody(required = false) byte[] body) {
        return proxyService.proxy(productServiceUrl, request, body);
    }

    /**
     * 新版评论 / 追问路由（归属 product 服务，接口由 T03 实现）。
     *
     * <p>路由与权限规则在 T02 先行落地，T03 只需补服务端实现，无需再动网关。
     *
     * @param request 原始请求
     * @param body    原始请求体，可为 null
     * @return 下游响应
     */
    @RequestMapping(value = "/comment/**", method = {RequestMethod.GET, RequestMethod.POST,
            RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxyCommentRequest(HttpServletRequest request,
                                                      @RequestBody(required = false) byte[] body) {
        return proxyService.proxy(productServiceUrl, request, body);
    }

    /**
     * 订单路由（含购物车）。
     *
     * @param request 原始请求
     * @param body    原始请求体，可为 null
     * @return 下游响应
     */
    @RequestMapping(value = "/order/**", method = {RequestMethod.GET, RequestMethod.POST,
            RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxyOrderRequest(HttpServletRequest request,
                                                    @RequestBody(required = false) byte[] body) {
        return proxyService.proxy(orderServiceUrl, request, body);
    }

    /**
     * 收货地址路由（归属 order 服务）。
     *
     * @param request 原始请求
     * @param body    原始请求体，可为 null
     * @return 下游响应
     */
    @RequestMapping(value = "/address/**", method = {RequestMethod.GET, RequestMethod.POST,
            RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxyAddressRequest(HttpServletRequest request,
                                                      @RequestBody(required = false) byte[] body) {
        return proxyService.proxy(orderServiceUrl, request, body);
    }

    /**
     * 移动端 BFF 路由。
     *
     * @param request 原始请求
     * @param body    原始请求体，可为 null
     * @return 下游响应
     */
    @RequestMapping(value = "/mobile/**", method = {RequestMethod.GET, RequestMethod.POST,
            RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxyMobileRequest(HttpServletRequest request,
                                                     @RequestBody(required = false) byte[] body) {
        return proxyService.proxy(mobileServiceUrl, request, body);
    }
}
