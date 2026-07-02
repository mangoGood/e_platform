package com.ecommerce.mobile.service;

import com.ecommerce.common.result.PageResult;
import com.ecommerce.common.result.Result;
import com.ecommerce.mobile.client.ProductClient;
import com.ecommerce.mobile.dto.CategoryInfo;
import com.ecommerce.mobile.dto.HomeDataVO;
import com.ecommerce.mobile.dto.ProductInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 首页聚合服务
 */
@Slf4j
@Service
public class MobileHomeService {

    @Autowired
    private ProductClient productClient;

    /**
     * 获取首页聚合数据：分类 + 推荐商品 + 新品 + 轮播图
     */
    public HomeDataVO getHomeData() {
        HomeDataVO vo = new HomeDataVO();

        // 分类树
        try {
            Result<List<CategoryInfo>> categoryResult = productClient.getCategoryTree();
            if (categoryResult != null && categoryResult.isSuccess()) {
                vo.setCategories(categoryResult.getData());
            }
        } catch (Exception e) {
            log.warn("获取分类树失败：{}", e.getMessage());
            vo.setCategories(Collections.emptyList());
        }

        // 推荐商品（取前 10）
        List<ProductInfo> products = fetchProducts(1, 10);
        vo.setRecommendProducts(products);

        // 新品上架 + 轮播图（取前 5 主图作为 banner）
        vo.setNewProducts(products);
        vo.setBanners(products.stream()
                .limit(5)
                .map(ProductInfo::getMainImage)
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));

        return vo;
    }

    /**
     * 拉取商品列表
     */
    private List<ProductInfo> fetchProducts(int current, int size) {
        try {
            Result<PageResult<ProductInfo>> result = productClient.getProductList(current, size, null, null);
            if (result != null && result.isSuccess() && result.getData() != null) {
                List<ProductInfo> records = result.getData().getRecords();
                return records != null ? records : Collections.emptyList();
            }
        } catch (Exception e) {
            log.warn("获取商品列表失败：{}", e.getMessage());
        }
        return Collections.emptyList();
    }
}
