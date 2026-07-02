package com.ecommerce.mobile.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 购物车聚合数据
 * <p>
 * 合并购物车项 + 商品详情，移动端无需二次查询商品信息。
 */
@Data
public class CartVO {
    /** 购物车项列表（含商品详情） */
    private List<CartItemVO> items;

    /** 选中商品总数量 */
    private Integer totalCount;

    /** 选中商品总金额 */
    private BigDecimal totalPrice;

    /** 是否全选 */
    private Boolean allSelected;

    /**
     * 购物车单项（含商品信息）
     */
    @Data
    public static class CartItemVO {
        private Long cartId;
        private Long productId;
        private Integer quantity;
        private Boolean selected;

        /** 商品详情 */
        private String productName;
        private String productImage;
        private BigDecimal price;
        private Integer stock;
        private Integer status;

        /** 小计金额 */
        private BigDecimal subtotal;
    }
}
