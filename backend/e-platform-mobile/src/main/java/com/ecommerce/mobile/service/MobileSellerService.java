package com.ecommerce.mobile.service;

import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.PageResult;
import com.ecommerce.common.result.Result;
import com.ecommerce.mobile.client.OrderClient;
import com.ecommerce.mobile.client.ProductClient;
import com.ecommerce.mobile.context.UserContext;
import com.ecommerce.mobile.dto.OrderVO;
import com.ecommerce.mobile.dto.ProductInfo;
import com.ecommerce.mobile.dto.SellerDashboardVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 卖家工作台聚合服务
 */
@Slf4j
@Service
public class MobileSellerService {

    @Autowired
    private ProductClient productClient;

    @Autowired
    private OrderClient orderClient;

    /**
     * 获取卖家工作台数据
     */
    public SellerDashboardVO getDashboard() {
        Long sellerId = UserContext.getUserId();
        Integer userType = UserContext.getUserType();

        if (userType == null || userType != 2) {
            throw new BusinessException(403, "只有卖家才能访问工作台");
        }

        SellerDashboardVO vo = new SellerDashboardVO();

        // 商品总数
        try {
            Result<List<ProductInfo>> productResult = productClient.getProductsBySellerId(sellerId);
            if (productResult != null && productResult.isSuccess() && productResult.getData() != null) {
                vo.setProductCount(productResult.getData().size());
            }
        } catch (Exception e) {
            log.warn("获取卖家商品数失败：{}", e.getMessage());
            vo.setProductCount(0);
        }

        // 订单统计（查第一页，取 total 字段）
        try {
            Result<PageResult<OrderVO>> orderResult = orderClient.getOrdersBySellerId(sellerId, 1, 5);
            if (orderResult != null && orderResult.isSuccess() && orderResult.getData() != null) {
                PageResult<OrderVO> page = orderResult.getData();
                vo.setOrderCount(page.getTotal());
                vo.setRecentOrders(page.getRecords());

                // 统计各状态订单数（需要全量查询，这里简化为遍历当前页）
                // 实际生产中应调用专门的统计接口
                long pendingCount = 0, deliveredCount = 0, completedCount = 0;
                if (page.getRecords() != null) {
                    for (OrderVO order : page.getRecords()) {
                        if (order.getStatus() != null) {
                            switch (order.getStatus()) {
                                case 1: // 待付款
                                case 2: // 待发货
                                    pendingCount++;
                                    break;
                                case 3: // 已发货
                                    deliveredCount++;
                                    break;
                                case 4: // 已完成
                                    completedCount++;
                                    break;
                            }
                        }
                    }
                }
                vo.setPendingDeliveryCount(pendingCount);
                vo.setDeliveredCount(deliveredCount);
                vo.setCompletedCount(completedCount);
            }
        } catch (Exception e) {
            log.warn("获取卖家订单统计失败：{}", e.getMessage());
            vo.setOrderCount(0L);
            vo.setPendingDeliveryCount(0L);
            vo.setDeliveredCount(0L);
            vo.setCompletedCount(0L);
        }

        return vo;
    }

    /**
     * 卖家商品列表
     */
    public List<ProductInfo> getMyProducts() {
        Long sellerId = UserContext.getUserId();
        Result<List<ProductInfo>> result = productClient.getProductsBySellerId(sellerId);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(result != null ? result.getMessage() : "获取商品列表失败");
        }
        return result.getData();
    }
}
