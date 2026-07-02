package com.ecommerce.mobile.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 订单 VO（含订单项）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderVO extends OrderInfo {
    private List<OrderItemInfo> items;
}
