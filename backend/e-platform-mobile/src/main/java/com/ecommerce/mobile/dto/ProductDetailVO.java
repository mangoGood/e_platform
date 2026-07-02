package com.ecommerce.mobile.dto;

import lombok.Data;

import java.util.List;

/**
 * 商品详情聚合数据
 * <p>
 * 合并商品基本信息 + 评价列表，移动端一次拉取。
 */
@Data
public class ProductDetailVO {
    /** 商品信息 */
    private ProductInfo product;

    /** 评价列表 */
    private List<ReviewInfo> reviews;

    /** 评价总数 */
    private Integer reviewCount;

    /** 平均评分 */
    private Double avgRating;
}
