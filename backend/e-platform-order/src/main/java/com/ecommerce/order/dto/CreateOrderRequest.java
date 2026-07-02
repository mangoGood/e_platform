package com.ecommerce.order.dto;

import javax.validation.constraints.NotNull;
import java.util.List;

public class CreateOrderRequest {
    @NotNull(message = "商品列表不能为空")
    private List<OrderItemRequest> items;
    
    private String receiverName;
    
    private String receiverPhone;
    
    private String receiverAddress;

    public List<OrderItemRequest> getItems() { return items; }
    public void setItems(List<OrderItemRequest> items) { this.items = items; }
    public String getReceiverName() { return receiverName; }
    public void setReceiverName(String receiverName) { this.receiverName = receiverName; }
    public String getReceiverPhone() { return receiverPhone; }
    public void setReceiverPhone(String receiverPhone) { this.receiverPhone = receiverPhone; }
    public String getReceiverAddress() { return receiverAddress; }
    public void setReceiverAddress(String receiverAddress) { this.receiverAddress = receiverAddress; }

    public static class OrderItemRequest {
        @NotNull(message = "商品ID不能为空")
        private Long productId;
        
        @NotNull(message = "数量不能为空")
        private Integer quantity;

        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
    }
}
