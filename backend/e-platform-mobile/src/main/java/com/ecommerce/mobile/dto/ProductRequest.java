package com.ecommerce.mobile.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品新增/编辑请求
 */
@Data
public class ProductRequest {
    private Long categoryId;
    private String name;
    private String description;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private Integer stock;
    private String mainImage;
    private String images;
    private Integer status;
}
