package com.ecommerce.mobile.controller;

import com.ecommerce.common.result.PageResult;
import com.ecommerce.common.result.Result;
import com.ecommerce.mobile.dto.OrderVO;
import com.ecommerce.mobile.dto.ProductInfo;
import com.ecommerce.mobile.dto.SellerDashboardVO;
import com.ecommerce.mobile.service.MobileOrderService;
import com.ecommerce.mobile.service.MobileSellerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 移动端卖家工作台接口
 */
@RestController
@RequestMapping("/mobile/seller")
public class MobileSellerController {

    @Autowired
    private MobileSellerService sellerService;

    @Autowired
    private MobileOrderService orderService;

    /**
     * 工作台数据（商品数 + 订单统计 + 最近订单）
     */
    @GetMapping("/dashboard")
    public Result<SellerDashboardVO> dashboard() {
        return Result.success(sellerService.getDashboard());
    }

    /**
     * 卖家商品列表
     */
    @GetMapping("/products")
    public Result<List<ProductInfo>> myProducts() {
        return Result.success(sellerService.getMyProducts());
    }

    /**
     * 卖家订单列表
     */
    @GetMapping("/orders")
    public Result<PageResult<OrderVO>> orders(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.success(orderService.getSellerOrders(current, size));
    }

    /**
     * 发货
     */
    @PostMapping("/orders/{orderId}/deliver")
    public Result<Void> deliver(@PathVariable Long orderId) {
        orderService.deliverOrder(orderId);
        return Result.success();
    }
}
