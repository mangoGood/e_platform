package com.ecommerce.mobile.client;

import com.ecommerce.common.result.PageResult;
import com.ecommerce.common.result.Result;
import com.ecommerce.mobile.dto.CategoryInfo;
import com.ecommerce.mobile.dto.ProductInfo;
import com.ecommerce.mobile.dto.ProductRequest;
import com.ecommerce.mobile.dto.ReviewInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 商品服务 Feign 客户端
 */
@FeignClient(name = "e-platform-product", url = "${service.product.url:http://localhost:8086}")
public interface ProductClient {

    @GetMapping("/product/list")
    Result<PageResult<ProductInfo>> getProductList(
            @RequestParam("current") Integer current,
            @RequestParam("size") Integer size,
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "keyword", required = false) String keyword);

    @GetMapping("/product/{id}")
    Result<ProductInfo> getProductById(@PathVariable("id") Long id);

    @GetMapping("/product/batch")
    Result<List<ProductInfo>> getProductsByIds(@RequestParam("ids") List<Long> ids);

    @GetMapping("/product/seller/{sellerId}")
    Result<List<ProductInfo>> getProductsBySellerId(@PathVariable("sellerId") Long sellerId);

    @PostMapping("/product/add")
    Result<Void> addProduct(@RequestBody ProductRequest request,
                            @RequestHeader("X-User-Id") Long sellerId,
                            @RequestHeader("X-User-Type") Integer userType);

    @PutMapping("/product/{id}")
    Result<Void> updateProduct(@PathVariable("id") Long id,
                               @RequestBody ProductRequest request,
                               @RequestHeader("X-User-Id") Long sellerId,
                               @RequestHeader("X-User-Type") Integer userType);

    @DeleteMapping("/product/{id}")
    Result<Void> deleteProduct(@PathVariable("id") Long id,
                               @RequestHeader("X-User-Id") Long sellerId,
                               @RequestHeader("X-User-Type") Integer userType);

    @GetMapping("/category/tree")
    Result<List<CategoryInfo>> getCategoryTree();

    @GetMapping("/category/all")
    Result<List<CategoryInfo>> getAllCategories();

    @GetMapping("/review/product/{productId}")
    Result<List<ReviewInfo>> getReviewsByProductId(@PathVariable("productId") Long productId);
}
