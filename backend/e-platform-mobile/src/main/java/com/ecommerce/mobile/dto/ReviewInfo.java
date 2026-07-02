package com.ecommerce.mobile.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品评价
 */
@Data
public class ReviewInfo {
    private Long id;
    private Long productId;
    private Long userId;
    private Long orderId;
    private Integer rating;
    private String content;
    private String images;
    private Integer status;
    private LocalDateTime createTime;
}
