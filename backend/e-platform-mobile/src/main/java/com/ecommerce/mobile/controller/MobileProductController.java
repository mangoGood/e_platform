package com.ecommerce.mobile.controller;

import com.ecommerce.common.result.PageResult;
import com.ecommerce.common.result.Result;
import com.ecommerce.mobile.dto.ProductDetailVO;
import com.ecommerce.mobile.dto.ProductInfo;
import com.ecommerce.mobile.dto.ProductRequest;
import com.ecommerce.mobile.service.MobileProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * 移动端商品接口
 */
@RestController
@RequestMapping("/mobile/products")
public class MobileProductController {

    @Autowired
    private MobileProductService productService;

    /**
     * 商品列表（公开）
     */
    @GetMapping
    public Result<PageResult<ProductInfo>> list(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String keyword) {
        return Result.success(productService.getProductList(current, size, categoryId, keyword));
    }

    /**
     * 商品详情（含评价，公开）
     */
    @GetMapping("/{id}")
    public Result<ProductDetailVO> detail(@PathVariable Long id) {
        return Result.success(productService.getProductDetail(id));
    }

    /**
     * 新增商品（卖家）
     */
    @PostMapping
    public Result<Void> add(@Valid @RequestBody ProductRequest request) {
        productService.addProduct(request);
        return Result.success();
    }

    /**
     * 编辑商品（卖家）
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        productService.updateProduct(id, request);
        return Result.success();
    }

    /**
     * 删除商品（卖家）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        productService.deleteProduct(id);
        return Result.success();
    }
}
