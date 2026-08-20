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

    /**
     * 卖家手动发货：{@code 待发货(1) → 待收货(2)}。
     *
     * <p>本项目没有对接真实物流，这条接口就是"物流系统回调"的人工替身。
     * 越权（非本店卖家）返回真实 <b>403</b>，状态不允许（重复发货、订单已取消等）返回真实 <b>409</b>。
     *
     * <p><b>注意</b>：网关侧 {@code /order/**} 统一挂的是 {@code order:read}，
     * 买卖双方都持有该权限码，所以网关<b>拦不住</b>越权发货——
     * 真正的边界在 {@code OrderService.shipOrder} 的归属校验里。
     * 这不是疏漏，是分层：网关管"能不能进这个域"，服务管"这条数据是不是你的"。
     *
     * @param orderId  订单 id
     * @param sellerId 网关下发的操作人 id
     * @return 空结果
     */
    @PostMapping("/{orderId}/ship")
    public Result<Void> shipOrder(@PathVariable Long orderId,
                                  @RequestHeader("X-User-Id") Long sellerId) {
        orderService.shipOrder(orderId, sellerId);
        return Result.success();
    }

    /**
     * 买家手动确认收货：{@code 待收货(2) → 已完成(3)}。
     *
     * <p>订单进入 {@code 已完成(3)} 之后，买家才获得该商品的评价资格。
     * 越权（非下单买家）返回真实 <b>403</b>；未发货就确认、或重复确认返回真实 <b>409</b>。
     *
     * @param orderId 订单 id
     * @param userId  网关下发的操作人 id
     * @return 空结果
     */
    @PostMapping("/{orderId}/confirm")
    public Result<Void> confirmOrder(@PathVariable Long orderId,
                                     @RequestHeader("X-User-Id") Long userId) {
        orderService.confirmOrder(orderId, userId);
        return Result.success();
    }

    /**
     * 查询订单详情，仅限<b>下单买家</b>或<b>订单所属卖家</b>。
     *
     * <p>归属校验在 {@link OrderService#getOrderByIdAndUserId} 内完成：
     * 订单不存在抛 404，订单不属于调用方抛 403，两者都由
     * {@code GlobalExceptionHandler} 映射成真实 HTTP 状态码。
     *
     * <p><b>这里不需要再对 null 做兜底</b>——service 现在要么返回一个确实属于
     * 调用方的订单，要么抛异常，契约上永不返回 null。此前 service 越权时返回 null，
     * 本方法原样 {@code Result.success(null)}，把越权访问包装成
     * {@code HTTP 200 + success:true}，是缺陷的最后一环。
     *
     * @param orderId 订单 id
     * @param userId  网关下发的操作人 id
     * @return 订单详情，data 非 null
     */
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
