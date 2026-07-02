package com.ecommerce.mobile.controller;

import com.ecommerce.common.result.PageResult;
import com.ecommerce.common.result.Result;
import com.ecommerce.mobile.dto.CreateOrderRequest;
import com.ecommerce.mobile.dto.OrderVO;
import com.ecommerce.mobile.service.MobileOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * 移动端订单接口
 */
@RestController
@RequestMapping("/mobile/orders")
public class MobileOrderController {

    @Autowired
    private MobileOrderService orderService;

    /**
     * 创建订单
     */
    @PostMapping
    public Result<List<OrderVO>> create(@Valid @RequestBody CreateOrderRequest request) {
        return Result.success(orderService.createOrder(request));
    }

    /**
     * 订单详情（含订单项）
     */
    @GetMapping("/{orderId}")
    public Result<OrderVO> detail(@PathVariable Long orderId) {
        return Result.success(orderService.getOrderDetail(orderId));
    }

    /**
     * 我的订单列表
     */
    @GetMapping("/my")
    public Result<PageResult<OrderVO>> myOrders(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.success(orderService.getMyOrders(current, size));
    }

    /**
     * 支付订单
     */
    @PostMapping("/{orderId}/pay")
    public Result<Void> pay(@PathVariable Long orderId) {
        orderService.payOrder(orderId);
        return Result.success();
    }

    /**
     * 取消订单
     */
    @PostMapping("/{orderId}/cancel")
    public Result<Void> cancel(@PathVariable Long orderId) {
        orderService.cancelOrder(orderId);
        return Result.success();
    }

    /**
     * 确认收货
     */
    @PostMapping("/{orderId}/receive")
    public Result<Void> receive(@PathVariable Long orderId) {
        orderService.receiveOrder(orderId);
        return Result.success();
    }
}
