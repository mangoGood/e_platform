package com.ecommerce.mobile.service;

import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.PageResult;
import com.ecommerce.common.result.Result;
import com.ecommerce.mobile.client.ProductClient;
import com.ecommerce.mobile.context.UserContext;
import com.ecommerce.mobile.dto.ProductDetailVO;
import com.ecommerce.mobile.dto.ProductInfo;
import com.ecommerce.mobile.dto.ProductRequest;
import com.ecommerce.mobile.dto.ReviewInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 商品聚合服务
 */
@Slf4j
@Service
public class MobileProductService {

    @Autowired
    private ProductClient productClient;

    /**
     * 获取商品详情 + 评价（聚合接口）
     */
    public ProductDetailVO getProductDetail(Long productId) {
        ProductDetailVO vo = new ProductDetailVO();

        // 商品信息
        Result<ProductInfo> productResult = productClient.getProductById(productId);
        if (productResult == null || !productResult.isSuccess() || productResult.getData() == null) {
            throw new BusinessException("商品不存在");
        }
        vo.setProduct(productResult.getData());

        // 评价列表
        try {
            Result<List<ReviewInfo>> reviewResult = productClient.getReviewsByProductId(productId);
            if (reviewResult != null && reviewResult.isSuccess()) {
                List<ReviewInfo> reviews = reviewResult.getData();
                vo.setReviews(reviews);
                vo.setReviewCount(reviews != null ? reviews.size() : 0);
                if (reviews != null && !reviews.isEmpty()) {
                    double avg = reviews.stream()
                            .mapToInt(ReviewInfo::getRating)
                            .average()
                            .orElse(5.0);
                    vo.setAvgRating(Math.round(avg * 10) / 10.0);
                } else {
                    vo.setAvgRating(5.0);
                }
            }
        } catch (Exception e) {
            log.warn("获取商品评价失败：{}", e.getMessage());
            vo.setReviewCount(0);
            vo.setAvgRating(5.0);
        }

        return vo;
    }

    /**
     * 商品列表（带分页）
     */
    public PageResult<ProductInfo> getProductList(Integer current, Integer size, Long categoryId, String keyword) {
        Result<PageResult<ProductInfo>> result = productClient.getProductList(current, size, categoryId, keyword);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(result != null ? result.getMessage() : "获取商品列表失败");
        }
        return result.getData();
    }

    /**
     * 批量查询商品
     */
    public List<ProductInfo> getProductsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Result<List<ProductInfo>> result = productClient.getProductsByIds(ids);
        if (result == null || !result.isSuccess()) {
            return List.of();
        }
        return result.getData();
    }

    /**
     * 新增商品（卖家）
     */
    public void addProduct(ProductRequest request) {
        Long sellerId = UserContext.getUserId();
        Integer userType = UserContext.getUserType();
        if (userType == null || userType != 2) {
            throw new BusinessException(403, "只有卖家才能添加商品");
        }
        Result<Void> result = productClient.addProduct(request, sellerId, userType);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(result != null ? result.getMessage() : "添加商品失败");
        }
    }

    /**
     * 编辑商品（卖家）
     */
    public void updateProduct(Long id, ProductRequest request) {
        Long sellerId = UserContext.getUserId();
        Integer userType = UserContext.getUserType();
        if (userType == null || userType != 2) {
            throw new BusinessException(403, "只有卖家才能修改商品");
        }
        Result<Void> result = productClient.updateProduct(id, request, sellerId, userType);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(result != null ? result.getMessage() : "修改商品失败");
        }
    }

    /**
     * 删除商品（卖家）
     */
    public void deleteProduct(Long id) {
        Long sellerId = UserContext.getUserId();
        Integer userType = UserContext.getUserType();
        if (userType == null || userType != 2) {
            throw new BusinessException(403, "只有卖家才能删除商品");
        }
        Result<Void> result = productClient.deleteProduct(id, sellerId, userType);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(result != null ? result.getMessage() : "删除商品失败");
        }
    }
}
