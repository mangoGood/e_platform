package com.ecommerce.mobile.dto;

import lombok.Data;

import java.util.List;

/**
 * 首页聚合数据
 * <p>
 * 一次请求返回首页所需的全部数据：分类树 + 推荐商品 + 热销商品，
 * 避免移动端发起多次请求。
 */
@Data
public class HomeDataVO {
    /** 分类树 */
    private List<CategoryInfo> categories;

    /** 推荐商品（按销量降序） */
    private List<ProductInfo> recommendProducts;

    /** 新品上架（按创建时间降序） */
    private List<ProductInfo> newProducts;

    /** 轮播图（暂用主图，可扩展为独立 banner 表） */
    private List<String> banners;
}
