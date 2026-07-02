package com.ecommerce.mobile.dto;

import lombok.Data;

import java.util.List;

/**
 * 卖家工作台聚合数据
 * <p>
 * 一次请求返回卖家首页所需的统计信息。
 */
@Data
public class SellerDashboardVO {
    /** 商品总数 */
    private Integer productCount;

    /** 订单总数 */
    private Long orderCount;

    /** 待发货订单数 */
    private Long pendingDeliveryCount;

    /** 已发货订单数 */
    private Long deliveredCount;

    /** 已完成订单数 */
    private Long completedCount;

    /** 最近订单（前 5 条） */
    private List<OrderVO> recentOrders;
}
