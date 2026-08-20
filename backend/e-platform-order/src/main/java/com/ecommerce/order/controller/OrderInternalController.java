package com.ecommerce.order.controller;

import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ErrorCode;
import com.ecommerce.common.result.Result;
import com.ecommerce.order.dto.PurchaseCheckVO;
import com.ecommerce.order.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单服务的<b>内部专用</b>接口，只对其他微服务开放。
 *
 * <h3>为什么这条接口必须存在</h3>
 * "购买并确认收货后才能评价"这条规则的判据在 orders/order_item 表里，
 * 而这两张表的所有权属于 order 服务。
 * product 服务既不该、也无法直接查它们——跨服务读别人的表是最典型的架构腐化起点。
 * 所以由 order 服务把判据封装成一条明确的查询接口对外提供。
 *
 * <h3>两道防线</h3>
 * <ol>
 *   <li><b>网关层</b>：{@code RoutePermissionRegistry} 对 {@code /**&#47;internal&#47;**} 一律
 *       {@code INTERNAL_DENY}，外部请求无论带什么身份都进不来；</li>
 *   <li><b>服务层</b>：本类校验 {@code X-Internal-Token}，
 *       防止有人绕过网关直连 8087 端口。</li>
 * </ol>
 * 只有一道会出问题——网关配置可能被改错，端口可能被暴露。两道都在才算数。
 */
@RestController
@RequestMapping("/order/internal")
public class OrderInternalController {

    @Autowired
    private OrderService orderService;

    /**
     * 服务间直连令牌。
     *
     * <p>与 product 侧同样<b>不设默认值</b>：把 {@code ePlatformInternalSecret2026}
     * 这类兜底口令写进源码，等于公开了内部接口的钥匙。
     * 未配置时一切内部调用被拒，逼迫部署方显式配置 {@code INTERNAL_TOKEN}。
     */
    @Value("${internal.token:}")
    private String internalToken = "";

    /**
     * 查询购买记录（供 product 服务做评价前置校验）。
     *
     * @param userId    买家 id
     * @param productId 商品 id
     * @param token     {@code X-Internal-Token} 请求头
     * @return 购买校验结果
     */
    @GetMapping("/purchased")
    public Result<PurchaseCheckVO> checkPurchased(
            @RequestParam Long userId,
            @RequestParam Long productId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        assertInternalToken(token);
        return Result.success(orderService.checkPurchased(userId, productId));
    }

    /**
     * 校验内部服务令牌，不通过抛真实 403。
     *
     * @param token 请求头中的 {@code X-Internal-Token}
     */
    private void assertInternalToken(String token) {
        if (internalToken == null || internalToken.isEmpty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "内部接口未启用（服务端未配置 INTERNAL_TOKEN）");
        }
        if (token == null || !internalToken.equals(token)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权限访问该接口");
        }
    }
}
