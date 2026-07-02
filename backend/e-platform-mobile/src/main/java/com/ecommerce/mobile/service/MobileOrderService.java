package com.ecommerce.mobile.service;

import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.PageResult;
import com.ecommerce.common.result.Result;
import com.ecommerce.mobile.client.OrderClient;
import com.ecommerce.mobile.context.UserContext;
import com.ecommerce.mobile.dto.CreateOrderRequest;
import com.ecommerce.mobile.dto.OrderInfo;
import com.ecommerce.mobile.dto.OrderItemInfo;
import com.ecommerce.mobile.dto.OrderVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 订单聚合服务
 */
@Slf4j
@Service
public class MobileOrderService {

    @Autowired
    private OrderClient orderClient;

    /**
     * 创建订单
     */
    public List<OrderVO> createOrder(CreateOrderRequest request) {
        Long userId = UserContext.getUserId();
        Result<List<OrderVO>> result = orderClient.createOrder(request, userId);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(result != null ? result.getMessage() : "创建订单失败");
        }
        return result.getData();
    }

    /**
     * 支付订单
     */
    public void payOrder(Long orderId) {
        Long userId = UserContext.getUserId();
        Result<Void> result = orderClient.payOrder(orderId, userId);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(result != null ? result.getMessage() : "支付失败");
        }
    }

    /**
     * 取消订单
     */
    public void cancelOrder(Long orderId) {
        Long userId = UserContext.getUserId();
        Result<Void> result = orderClient.cancelOrder(orderId, userId);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(result != null ? result.getMessage() : "取消订单失败");
        }
    }

    /**
     * 确认收货
     */
    public void receiveOrder(Long orderId) {
        Long userId = UserContext.getUserId();
        Result<Void> result = orderClient.receiveOrder(orderId, userId);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(result != null ? result.getMessage() : "确认收货失败");
        }
    }

    /**
     * 获取订单详情（含订单项）
     */
    public OrderVO getOrderDetail(Long orderId) {
        Long userId = UserContext.getUserId();

        // 查询订单基本信息
        Result<OrderInfo> orderResult = orderClient.getOrderById(orderId, userId);
        if (orderResult == null || !orderResult.isSuccess() || orderResult.getData() == null) {
            throw new BusinessException("订单不存在");
        }

        OrderInfo orderInfo = orderResult.getData();
        OrderVO vo = new OrderVO();
        copyOrderProperties(orderInfo, vo);

        // 查询订单项
        try {
            Result<List<OrderItemInfo>> itemsResult = orderClient.getOrderItems(orderId);
            if (itemsResult != null && itemsResult.isSuccess()) {
                vo.setItems(itemsResult.getData());
            }
        } catch (Exception e) {
            log.warn("获取订单项失败：{}", e.getMessage());
        }

        return vo;
    }

    /**
     * 买家订单列表
     */
    public PageResult<OrderVO> getMyOrders(Integer current, Integer size) {
        Long userId = UserContext.getUserId();
        Result<PageResult<OrderVO>> result = orderClient.getOrdersByUserId(userId, current, size);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(result != null ? result.getMessage() : "获取订单列表失败");
        }
        return result.getData();
    }

    /**
     * 卖家订单列表
     */
    public PageResult<OrderVO> getSellerOrders(Integer current, Integer size) {
        Long sellerId = UserContext.getUserId();
        Result<PageResult<OrderVO>> result = orderClient.getOrdersBySellerId(sellerId, current, size);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(result != null ? result.getMessage() : "获取订单列表失败");
        }
        return result.getData();
    }

    /**
     * 卖家发货
     */
    public void deliverOrder(Long orderId) {
        Long sellerId = UserContext.getUserId();
        Result<Void> result = orderClient.deliverOrder(orderId, sellerId);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(result != null ? result.getMessage() : "发货失败");
        }
    }

    private void copyOrderProperties(OrderInfo source, OrderVO target) {
        target.setId(source.getId());
        target.setOrderNo(source.getOrderNo());
        target.setUserId(source.getUserId());
        target.setSellerId(source.getSellerId());
        target.setTotalAmount(source.getTotalAmount());
        target.setPayAmount(source.getPayAmount());
        target.setFreightAmount(source.getFreightAmount());
        target.setStatus(source.getStatus());
        target.setReceiverName(source.getReceiverName());
        target.setReceiverPhone(source.getReceiverPhone());
        target.setReceiverAddress(source.getReceiverAddress());
        target.setPayTime(source.getPayTime());
        target.setDeliveryTime(source.getDeliveryTime());
        target.setReceiveTime(source.getReceiveTime());
        target.setCreateTime(source.getCreateTime());
        target.setUpdateTime(source.getUpdateTime());
    }
}
