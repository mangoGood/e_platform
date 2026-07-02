package com.ecommerce.mobile.client;

import com.ecommerce.common.result.PageResult;
import com.ecommerce.common.result.Result;
import com.ecommerce.mobile.dto.AddressInfo;
import com.ecommerce.mobile.dto.CartInfo;
import com.ecommerce.mobile.dto.CreateOrderRequest;
import com.ecommerce.mobile.dto.OrderInfo;
import com.ecommerce.mobile.dto.OrderItemInfo;
import com.ecommerce.mobile.dto.OrderVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 订单服务 Feign 客户端
 */
@FeignClient(name = "e-platform-order", url = "${service.order.url:http://localhost:8087}")
public interface OrderClient {

    // ========== 订单 ==========

    @PostMapping("/order/create")
    Result<List<OrderVO>> createOrder(@RequestBody CreateOrderRequest request,
                                      @RequestHeader("X-User-Id") Long userId);

    @PostMapping("/order/pay/{orderId}")
    Result<Void> payOrder(@PathVariable("orderId") Long orderId,
                          @RequestHeader("X-User-Id") Long userId);

    @PostMapping("/order/deliver/{orderId}")
    Result<Void> deliverOrder(@PathVariable("orderId") Long orderId,
                              @RequestHeader("X-User-Id") Long sellerId);

    @PostMapping("/order/receive/{orderId}")
    Result<Void> receiveOrder(@PathVariable("orderId") Long orderId,
                              @RequestHeader("X-User-Id") Long userId);

    @PostMapping("/order/cancel/{orderId}")
    Result<Void> cancelOrder(@PathVariable("orderId") Long orderId,
                             @RequestHeader("X-User-Id") Long userId);

    @GetMapping("/order/{orderId}")
    Result<OrderInfo> getOrderById(@PathVariable("orderId") Long orderId,
                                   @RequestHeader("X-User-Id") Long userId);

    @GetMapping("/order/user")
    Result<PageResult<OrderVO>> getOrdersByUserId(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam("current") Integer current,
            @RequestParam("size") Integer size);

    @GetMapping("/order/seller")
    Result<PageResult<OrderVO>> getOrdersBySellerId(
            @RequestHeader("X-User-Id") Long sellerId,
            @RequestParam("current") Integer current,
            @RequestParam("size") Integer size);

    @GetMapping("/order/items/{orderId}")
    Result<List<OrderItemInfo>> getOrderItems(@PathVariable("orderId") Long orderId);

    // ========== 购物车 ==========

    @PostMapping("/order/cart/add")
    Result<Void> addToCart(@RequestParam("productId") Long productId,
                           @RequestParam("quantity") Integer quantity,
                           @RequestHeader("X-User-Id") Long userId);

    @PutMapping("/order/cart")
    Result<Void> updateCartQuantity(@RequestParam("productId") Long productId,
                                    @RequestParam("quantity") Integer quantity,
                                    @RequestHeader("X-User-Id") Long userId);

    @DeleteMapping("/order/cart/{productId}")
    Result<Void> removeFromCart(@PathVariable("productId") Long productId,
                                @RequestHeader("X-User-Id") Long userId);

    @DeleteMapping("/order/cart")
    Result<Void> clearCart(@RequestHeader("X-User-Id") Long userId);

    @GetMapping("/order/cart")
    Result<List<CartInfo>> getCart(@RequestHeader("X-User-Id") Long userId);

    // ========== 收货地址 ==========

    @GetMapping("/address/list")
    Result<List<AddressInfo>> getAddresses(@RequestHeader("X-User-Id") Long userId);

    @GetMapping("/address/{id}")
    Result<AddressInfo> getAddress(@PathVariable("id") Long id,
                                   @RequestHeader("X-User-Id") Long userId);

    @PostMapping("/address")
    Result<AddressInfo> addAddress(@RequestBody AddressInfo address,
                                   @RequestHeader("X-User-Id") Long userId);

    @PutMapping("/address")
    Result<AddressInfo> updateAddress(@RequestBody AddressInfo address,
                                      @RequestHeader("X-User-Id") Long userId);

    @DeleteMapping("/address/{id}")
    Result<Void> deleteAddress(@PathVariable("id") Long id,
                               @RequestHeader("X-User-Id") Long userId);

    @PutMapping("/address/default/{id}")
    Result<Void> setDefaultAddress(@PathVariable("id") Long id,
                                   @RequestHeader("X-User-Id") Long userId);
}
