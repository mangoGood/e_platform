package com.ecommerce.mobile.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 购物车项
 */
@Data
public class CartInfo {
    private Long id;
    private Long userId;
    private Long productId;
    private Integer quantity;
    private Integer selected;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
