package com.ecommerce.mobile.controller;

import com.ecommerce.common.result.Result;
import com.ecommerce.mobile.dto.CartVO;
import com.ecommerce.mobile.service.MobileCartService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 移动端购物车接口
 * <p>
 * 获取购物车时自动合并商品详情，移动端无需二次查询。
 */
@RestController
@RequestMapping("/mobile/cart")
public class MobileCartController {

    @Autowired
    private MobileCartService cartService;

    /**
     * 获取购物车（含商品详情）
     */
    @GetMapping
    public Result<CartVO> getCart() {
        return Result.success(cartService.getCart());
    }

    /**
     * 加入购物车
     */
    @PostMapping("/add")
    public Result<Void> addToCart(@RequestParam Long productId,
                                  @RequestParam Integer quantity) {
        cartService.addToCart(productId, quantity);
        return Result.success();
    }

    /**
     * 修改数量
     */
    @PutMapping("/quantity")
    public Result<Void> updateQuantity(@RequestParam Long productId,
                                       @RequestParam Integer quantity) {
        cartService.updateQuantity(productId, quantity);
        return Result.success();
    }

    /**
     * 移除商品
     */
    @DeleteMapping("/{productId}")
    public Result<Void> removeFromCart(@PathVariable Long productId) {
        cartService.removeFromCart(productId);
        return Result.success();
    }

    /**
     * 清空购物车
     */
    @DeleteMapping
    public Result<Void> clearCart() {
        cartService.clearCart();
        return Result.success();
    }
}
