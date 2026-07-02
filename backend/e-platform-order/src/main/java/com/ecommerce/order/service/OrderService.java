package com.ecommerce.order.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.PageResult;
import com.ecommerce.common.result.Result;
import com.ecommerce.order.client.ProductClient;
import com.ecommerce.order.dto.CreateOrderRequest;
import com.ecommerce.order.dto.OrderVO;
import com.ecommerce.order.entity.Cart;
import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OrderItem;
import com.ecommerce.order.mapper.CartMapper;
import com.ecommerce.order.mapper.OrderItemMapper;
import com.ecommerce.order.mapper.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private CartMapper cartMapper;

    @Autowired
    private ProductClient productClient;

    /**
     * 创建订单 - 按卖家分组，每个卖家生成一个独立订单
     */
    @Transactional(rollbackFor = Exception.class)
    public List<OrderVO> createOrder(CreateOrderRequest request, Long userId) {
        // 1. 一次性查询所有商品信息并缓存，避免重复查询
        Map<Long, ProductClient.ProductInfo> productMap = new HashMap<>();
        for (CreateOrderRequest.OrderItemRequest itemRequest : request.getItems()) {
            if (!productMap.containsKey(itemRequest.getProductId())) {
                Result<ProductClient.ProductInfo> productResult = productClient.getProductById(itemRequest.getProductId());
                if (productResult == null || productResult.getData() == null) {
                    throw new BusinessException("商品不存在，商品ID: " + itemRequest.getProductId());
                }
                productMap.put(itemRequest.getProductId(), productResult.getData());
            }
        }

        // 2. 校验库存
        for (CreateOrderRequest.OrderItemRequest itemRequest : request.getItems()) {
            ProductClient.ProductInfo product = productMap.get(itemRequest.getProductId());
            if (product.getStock() < itemRequest.getQuantity()) {
                throw new BusinessException("商品库存不足: " + product.getName());
            }
        }

        // 3. 按卖家分组
        Map<Long, List<CreateOrderRequest.OrderItemRequest>> sellerItemsMap = new LinkedHashMap<>();
        for (CreateOrderRequest.OrderItemRequest itemRequest : request.getItems()) {
            Long sellerId = productMap.get(itemRequest.getProductId()).getSellerId();
            sellerItemsMap.computeIfAbsent(sellerId, k -> new ArrayList<>()).add(itemRequest);
        }

        // 4. 为每个卖家创建订单
        List<OrderVO> createdOrders = new ArrayList<>();
        Map<Long, Integer> deductedStock = new LinkedHashMap<>();

        try {
            for (Map.Entry<Long, List<CreateOrderRequest.OrderItemRequest>> entry : sellerItemsMap.entrySet()) {
                Long sellerId = entry.getKey();
                List<CreateOrderRequest.OrderItemRequest> items = entry.getValue();

                Order order = new Order();
                order.setOrderNo(IdUtil.getSnowflakeNextIdStr());
                order.setUserId(userId);
                order.setSellerId(sellerId);
                order.setStatus(0);
                order.setReceiverName(request.getReceiverName());
                order.setReceiverPhone(request.getReceiverPhone());
                order.setReceiverAddress(request.getReceiverAddress());
                order.setFreightAmount(BigDecimal.ZERO);

                BigDecimal totalAmount = BigDecimal.ZERO;
                List<OrderItem> orderItems = new ArrayList<>();

                for (CreateOrderRequest.OrderItemRequest itemRequest : items) {
                    ProductClient.ProductInfo product = productMap.get(itemRequest.getProductId());

                    OrderItem orderItem = new OrderItem();
                    orderItem.setProductId(itemRequest.getProductId());
                    orderItem.setQuantity(itemRequest.getQuantity());
                    orderItem.setPrice(product.getPrice());
                    orderItem.setTotalAmount(product.getPrice().multiply(BigDecimal.valueOf(itemRequest.getQuantity())));
                    orderItem.setProductName(product.getName());
                    orderItem.setProductImage(product.getMainImage());

                    totalAmount = totalAmount.add(orderItem.getTotalAmount());
                    orderItems.add(orderItem);
                }

                order.setTotalAmount(totalAmount);
                order.setPayAmount(totalAmount.add(order.getFreightAmount()));

                orderMapper.insert(order);

                for (OrderItem orderItem : orderItems) {
                    orderItem.setOrderId(order.getId());
                    orderItemMapper.insert(orderItem);

                    // 扣减库存
                    productClient.deductStock(orderItem.getProductId(), orderItem.getQuantity());
                    deductedStock.put(orderItem.getProductId(),
                            deductedStock.getOrDefault(orderItem.getProductId(), 0) + orderItem.getQuantity());
                }

                OrderVO vo = new OrderVO();
                org.springframework.beans.BeanUtils.copyProperties(order, vo);
                vo.setItems(orderItems);
                createdOrders.add(vo);
            }
        } catch (Exception e) {
            // 补偿：回滚已扣减的库存
            for (Map.Entry<Long, Integer> stockEntry : deductedStock.entrySet()) {
                try {
                    productClient.restoreStock(stockEntry.getKey(), stockEntry.getValue());
                } catch (Exception ex) {
                    log.error("库存回滚失败，productId={}, quantity={}", stockEntry.getKey(), stockEntry.getValue(), ex);
                }
            }
            throw e;
        }

        return createdOrders;
    }

    public void payOrder(Long orderId, Long userId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }

        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("无权限操作该订单");
        }

        if (order.getStatus() != 0) {
            throw new BusinessException("订单状态不正确");
        }

        order.setStatus(1);
        order.setPayTime(LocalDateTime.now());
        orderMapper.updateById(order);
    }

    public void deliverOrder(Long orderId, Long sellerId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }

        if (!order.getSellerId().equals(sellerId)) {
            throw new BusinessException("无权限操作该订单");
        }

        if (order.getStatus() != 1) {
            throw new BusinessException("订单状态不正确");
        }

        order.setStatus(2);
        order.setDeliveryTime(LocalDateTime.now());
        orderMapper.updateById(order);
    }

    public void receiveOrder(Long orderId, Long userId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }

        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("无权限操作该订单");
        }

        if (order.getStatus() != 2) {
            throw new BusinessException("订单状态不正确");
        }

        order.setStatus(3);
        order.setReceiveTime(LocalDateTime.now());
        orderMapper.updateById(order);
    }

    public void cancelOrder(Long orderId, Long userId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }

        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("无权限操作该订单");
        }

        if (order.getStatus() != 0) {
            throw new BusinessException("只能取消待付款的订单");
        }

        List<OrderItem> items = getOrderItemsByOrderId(orderId);
        for (OrderItem item : items) {
            productClient.restoreStock(item.getProductId(), item.getQuantity());
        }

        order.setStatus(4);
        orderMapper.updateById(order);
    }

    public Order getOrderById(Long orderId) {
        return orderMapper.selectById(orderId);
    }

    public Order getOrderByIdAndUserId(Long orderId, Long userId) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getId, orderId)
               .and(w -> w.eq(Order::getUserId, userId).or().eq(Order::getSellerId, userId));
        return orderMapper.selectOne(wrapper);
    }

    public List<Order> getOrdersByUserId(Long userId) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getUserId, userId)
               .orderByDesc(Order::getCreateTime);
        return orderMapper.selectList(wrapper);
    }

    public List<Order> getOrdersBySellerId(Long sellerId) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getSellerId, sellerId)
               .orderByDesc(Order::getCreateTime);
        return orderMapper.selectList(wrapper);
    }

    public PageResult<OrderVO> getOrdersWithItemsByUserId(Long userId, Integer current, Integer size) {
        Page<Order> page = new Page<>(current, size);
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getUserId, userId).orderByDesc(Order::getCreateTime);
        Page<Order> orderPage = orderMapper.selectPage(page, wrapper);
        List<OrderVO> voList = convertToVOList(orderPage.getRecords());
        return new PageResult<>(voList, orderPage.getTotal(), orderPage.getSize(), orderPage.getCurrent());
    }

    public PageResult<OrderVO> getOrdersWithItemsBySellerId(Long sellerId, Integer current, Integer size) {
        Page<Order> page = new Page<>(current, size);
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getSellerId, sellerId).orderByDesc(Order::getCreateTime);
        Page<Order> orderPage = orderMapper.selectPage(page, wrapper);
        List<OrderVO> voList = convertToVOList(orderPage.getRecords());
        return new PageResult<>(voList, orderPage.getTotal(), orderPage.getSize(), orderPage.getCurrent());
    }

    private List<OrderVO> convertToVOList(List<Order> orders) {
        return orders.stream().map(order -> {
            OrderVO vo = new OrderVO();
            org.springframework.beans.BeanUtils.copyProperties(order, vo);
            vo.setItems(getOrderItemsByOrderId(order.getId()));
            return vo;
        }).collect(Collectors.toList());
    }

    public List<OrderItem> getOrderItemsByOrderId(Long orderId) {
        LambdaQueryWrapper<OrderItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderItem::getOrderId, orderId);
        return orderItemMapper.selectList(wrapper);
    }
}
