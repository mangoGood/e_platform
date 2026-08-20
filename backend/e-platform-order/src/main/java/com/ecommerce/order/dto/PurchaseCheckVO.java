package com.ecommerce.order.dto;

import java.io.Serializable;

/**
 * 购买校验结果，供 product 服务在写入 L1 评价前调用。
 *
 * <h3>为什么要返回 {@code orderStatus} 而不是只返回一个 boolean</h3>
 * "不能评价"有两种截然不同的原因，用户看到的提示也应该不同：
 * <ul>
 *   <li>压根没买过 → {@code orderStatus = null}，提示"购买并确认收货后才能评价"；</li>
 *   <li>买了但还没收货 → {@code orderStatus = 0/1/2}，提示"确认收货后才能评价"。</li>
 * </ul>
 * 只回 boolean 的话，前端只能给出一句含糊的通用文案。
 */
public class PurchaseCheckVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否存在「已完成（status=3）」的购买记录。 */
    private Boolean purchased = Boolean.FALSE;

    /** 命中的订单 id，未购买时为 null。 */
    private Long orderId;

    /** 命中的订单明细 id，未购买时为 null。 */
    private Long orderItemId;

    /** 命中订单的当前状态；完全没买过时为 null。 */
    private Integer orderStatus;

    /** 确认收货时间（{@code yyyy-MM-dd HH:mm:ss}），未收货时为 null。 */
    private String receiveTime;

    public Boolean getPurchased() {
        return purchased;
    }

    public void setPurchased(Boolean purchased) {
        this.purchased = purchased;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getOrderItemId() {
        return orderItemId;
    }

    public void setOrderItemId(Long orderItemId) {
        this.orderItemId = orderItemId;
    }

    public Integer getOrderStatus() {
        return orderStatus;
    }

    public void setOrderStatus(Integer orderStatus) {
        this.orderStatus = orderStatus;
    }

    public String getReceiveTime() {
        return receiveTime;
    }

    public void setReceiveTime(String receiveTime) {
        this.receiveTime = receiveTime;
    }
}
