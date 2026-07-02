package com.ecommerce.mobile.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 创建订单请求
 */
@Data
public class CreateOrderRequest {
    @NotNull(message = "商品列表不能为空")
    private List<OrderItemRequest> items;

    private String receiverName;
    private String receiverPhone;
    private String receiverAddress;

    @Data
    public static class OrderItemRequest {
        @NotNull(message = "商品ID不能为空")
        private Long productId;

        @NotNull(message = "数量不能为空")
        private Integer quantity;
    }
}
