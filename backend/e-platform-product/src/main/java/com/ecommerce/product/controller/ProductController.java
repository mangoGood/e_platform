package com.ecommerce.product.controller;

import com.ecommerce.common.result.PageResult;
import com.ecommerce.common.result.Result;
import com.ecommerce.product.dto.ProductRequest;
import com.ecommerce.product.entity.Product;
import com.ecommerce.product.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/product")
public class ProductController {
    @Autowired
    private ProductService productService;

    @Value("${internal.token:ePlatformInternalSecret2026}")
    private String internalToken;

    @PostMapping("/add")
    public Result<Void> addProduct(@Valid @RequestBody ProductRequest request,
                                   @RequestHeader("X-User-Id") Long sellerId,
                                   @RequestHeader(value = "X-User-Type", required = false) Integer userType) {
        if (userType == null || userType != 2) {
            return Result.error(403, "只有卖家才能添加商品");
        }
        productService.addProduct(request, sellerId);
        return Result.success();
    }

    @PutMapping("/{id}")
    public Result<Void> updateProduct(@PathVariable Long id,
                                     @Valid @RequestBody ProductRequest request,
                                     @RequestHeader("X-User-Id") Long sellerId,
                                     @RequestHeader(value = "X-User-Type", required = false) Integer userType) {
        if (userType == null || userType != 2) {
            return Result.error(403, "只有卖家才能修改商品");
        }
        productService.updateProduct(id, request, sellerId);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteProduct(@PathVariable Long id,
                                     @RequestHeader("X-User-Id") Long sellerId,
                                     @RequestHeader(value = "X-User-Type", required = false) Integer userType) {
        if (userType == null || userType != 2) {
            return Result.error(403, "只有卖家才能删除商品");
        }
        productService.deleteProduct(id, sellerId);
        return Result.success();
    }

    @GetMapping("/{id}")
    public Result<Product> getProductById(@PathVariable Long id) {
        Product product = productService.getProductById(id);
        return Result.success(product);
    }

    @GetMapping("/list")
    public Result<PageResult<Product>> getProductList(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String keyword) {
        PageResult<Product> result = productService.getProductList(current, size, categoryId, keyword);
        return Result.success(result);
    }

    @GetMapping("/seller/{sellerId}")
    public Result<List<Product>> getProductsBySellerId(@PathVariable Long sellerId) {
        List<Product> products = productService.getProductsBySellerId(sellerId);
        return Result.success(products);
    }

    @GetMapping("/batch")
    public Result<List<Product>> getProductsByIds(@RequestParam List<Long> ids) {
        List<Product> products = productService.getProductsByIds(ids);
        return Result.success(products);
    }

    @PutMapping("/{id}/deduct")
    public Result<Void> deductStock(@PathVariable Long id, @RequestParam Integer quantity,
                                    @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (token == null || !token.equals(internalToken)) {
            return Result.error(403, "无权限访问该接口");
        }
        productService.deductStock(id, quantity);
        return Result.success();
    }

    @PutMapping("/{id}/restore")
    public Result<Void> restoreStock(@PathVariable Long id, @RequestParam Integer quantity,
                                     @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (token == null || !token.equals(internalToken)) {
            return Result.error(403, "无权限访问该接口");
        }
        productService.restoreStock(id, quantity);
        return Result.success();
    }
}
