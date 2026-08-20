package com.ecommerce.product.client;

import com.ecommerce.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.Serializable;

/**
 * 订单服务 Feign 客户端。
 *
 * <p>只有一个用途：L1 评价前校验「该用户确实买过该商品且订单已完成（status=3）」。
 *
 * <p>调用的 {@code /order/internal/purchased} 是内部专用接口——
 * 网关的 {@code RoutePermissionRegistry} 对 {@code /**&#47;internal&#47;**} 一律 INTERNAL_DENY，
 * 外部无论持何种身份都拿不到；本客户端走 Feign 直连 8087，
 * 由 {@code FeignConfig} 挂上的 {@code X-Internal-Token} 通过下游校验。
 */
@FeignClient(name = "e-platform-order-client", url = "${service.order.url:http://localhost:8087}",
        configuration = com.ecommerce.product.config.FeignConfig.class)
public interface OrderClient {

    /**
     * 查询购买记录。
     *
     * @param userId    买家 id
     * @param productId 商品 id
     * @return 购买校验结果；{@code purchased=false} 表示未购买或订单未完成
     */
    @GetMapping("/order/internal/purchased")
    Result<PurchaseCheck> checkPurchased(@RequestParam("userId") Long userId,
                                         @RequestParam("productId") Long productId);

    /** 购买校验结果传输对象，字段与 order 侧 {@code PurchaseCheckVO} 一一对应。 */
    class PurchaseCheck implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 是否存在「已完成」的购买记录。 */
        private Boolean purchased = Boolean.FALSE;

        /** 命中的订单 id，未购买时为 null。 */
        private Long orderId;

        /** 命中的订单明细 id，未购买时为 null。 */
        private Long orderItemId;

        /** 该订单当前状态，便于区分"没买过"与"买了但没收货"。 */
        private Integer orderStatus;

        /** 确认收货时间，字符串形式，仅用于展示。 */
        private String receiveTime;

        /**
         * @return 是否已购买且订单完成，null 视为 false
         */
        public boolean purchasedFlag() {
            return Boolean.TRUE.equals(purchased);
        }

        public Boolean getPurchased() {
            return purchased;
        }

        public void setPurchased(Boolean purchased) {
            this.purchased = purchased;
        }

        public Long getOrderId() {
            return orderId;
        }

        public void setOrderId(Long orderId) {
            this.orderId = orderId;
        }

        public Long getOrderItemId() {
            return orderItemId;
        }

        public void setOrderItemId(Long orderItemId) {
            this.orderItemId = orderItemId;
        }

        public Integer getOrderStatus() {
            return orderStatus;
        }

        public void setOrderStatus(Integer orderStatus) {
            this.orderStatus = orderStatus;
        }

        public String getReceiveTime() {
            return receiveTime;
        }

        public void setReceiveTime(String receiveTime) {
            this.receiveTime = receiveTime;
        }
    }
}
