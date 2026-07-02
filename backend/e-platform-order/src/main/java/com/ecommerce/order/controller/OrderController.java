package com.ecommerce.order.controller;

import com.ecommerce.common.result.PageResult;
import com.ecommerce.common.result.Result;
import com.ecommerce.order.dto.CreateOrderRequest;
import com.ecommerce.order.dto.OrderVO;
import com.ecommerce.order.entity.Cart;
import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OrderItem;
import com.ecommerce.order.service.CartService;
import com.ecommerce.order.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/order")
public class OrderController {
    @Autowired
    private OrderService orderService;
    
    @Autowired
    private CartService cartService;

    @PostMapping("/create")
    public Result<List<OrderVO>> createOrder(@Valid @RequestBody CreateOrderRequest request,
                                    @RequestHeader("X-User-Id") Long userId) {
        List<OrderVO> orders = orderService.createOrder(request, userId);
        return Result.success(orders);
    }

    @PostMapping("/pay/{orderId}")
    public Result<Void> payOrder(@PathVariable Long orderId,
                                @RequestHeader("X-User-Id") Long userId) {
        orderService.payOrder(orderId, userId);
        return Result.success();
    }

    @PostMapping("/deliver/{orderId}")
    public Result<Void> deliverOrder(@PathVariable Long orderId,
                                    @RequestHeader("X-User-Id") Long sellerId) {
        orderService.deliverOrder(orderId, sellerId);
        return Result.success();
    }

    @PostMapping("/receive/{orderId}")
    public Result<Void> receiveOrder(@PathVariable Long orderId,
                                    @RequestHeader("X-User-Id") Long userId) {
        orderService.receiveOrder(orderId, userId);
        return Result.success();
    }

    @PostMapping("/cancel/{orderId}")
    public Result<Void> cancelOrder(@PathVariable Long orderId,
                                   @RequestHeader("X-User-Id") Long userId) {
        orderService.cancelOrder(orderId, userId);
        return Result.success();
    }

    @GetMapping("/{orderId}")
    public Result<Order> getOrderById(@PathVariable Long orderId,
                                      @RequestHeader("X-User-Id") Long userId) {
        Order order = orderService.getOrderByIdAndUserId(orderId, userId);
        return Result.success(order);
    }

    @GetMapping("/user")
    public Result<PageResult<OrderVO>> getOrdersByUserId(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size) {
        PageResult<OrderVO> result = orderService.getOrdersWithItemsByUserId(userId, current, size);
        return Result.success(result);
    }

    @GetMapping("/seller")
    public Result<PageResult<OrderVO>> getOrdersBySellerId(
            @RequestHeader("X-User-Id") Long sellerId,
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size) {
        PageResult<OrderVO> result = orderService.getOrdersWithItemsBySellerId(sellerId, current, size);
        return Result.success(result);
    }

    @GetMapping("/items/{orderId}")
    public Result<List<OrderItem>> getOrderItems(@PathVariable Long orderId) {
        List<OrderItem> items = orderService.getOrderItemsByOrderId(orderId);
        return Result.success(items);
    }

    @PostMapping("/cart/add")
    public Result<Void> addToCart(@RequestParam Long productId,
                                  @RequestParam Integer quantity,
                                  @RequestHeader("X-User-Id") Long userId) {
        cartService.addToCart(userId, productId, quantity);
        return Result.success();
    }

    @PutMapping("/cart")
    public Result<Void> updateCartQuantity(@RequestParam Long productId,
                                          @RequestParam Integer quantity,
                                          @RequestHeader("X-User-Id") Long userId) {
        cartService.updateCartQuantity(userId, productId, quantity);
        return Result.success();
    }

    @DeleteMapping("/cart/{productId}")
    public Result<Void> removeFromCart(@PathVariable Long productId,
                                      @RequestHeader("X-User-Id") Long userId) {
        cartService.removeFromCart(userId, productId);
        return Result.success();
    }

    @DeleteMapping("/cart")
    public Result<Void> clearCart(@RequestHeader("X-User-Id") Long userId) {
        cartService.clearCart(userId);
        return Result.success();
    }

    @GetMapping("/cart")
    public Result<List<Cart>> getCart(@RequestHeader("X-User-Id") Long userId) {
        List<Cart> cart = cartService.getCartByUserId(userId);
        return Result.success(cart);
    }
}
