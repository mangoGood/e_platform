package com.ecommerce.product.vo;

import java.io.Serializable;

/**
 * 「我能不能评价这个商品」的判定结果。
 *
 * <p>供商品详情页在<b>渲染前</b>决定评价入口的形态：可点 / 置灰 + 提示文案。
 * 判定链与真正写入时完全一致（同一段服务端代码），不会出现"按钮亮着但点了报错"。
 */
public class CanReviewVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否可以评价。 */
    private boolean canReview = false;

    /** 不可评价的原因，可直接展示；可评价时为 null。 */
    private String reason;

    /** 将要绑定的订单 id，不可评价时可能为 null。 */
    private Long orderId;

    /** 将要绑定的订单明细 id，不可评价时可能为 null。 */
    private Long orderItemId;

    /** 命中订单的当前状态，用于区分"没买过"(null) 与"买了但没收货"(0/1/2)。 */
    private Integer orderStatus;

    public boolean isCanReview() {
        return canReview;
    }

    public void setCanReview(boolean canReview) {
        this.canReview = canReview;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
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
}
