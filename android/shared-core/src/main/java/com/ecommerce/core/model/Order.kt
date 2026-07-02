package com.ecommerce.core.model

import kotlinx.serialization.Serializable

/**
 * 订单实体，对应后端 com.ecommerce.order.entity.Order
 */
@Serializable
data class Order(
    val id: Long = 0,
    val orderNo: String = "",
    val userId: Long = 0,
    val sellerId: Long = 0,
    val totalAmount: Double = 0.0,
    val payAmount: Double = 0.0,
    val freightAmount: Double = 0.0,
    val status: Int = 0,
    val receiverName: String? = null,
    val receiverPhone: String? = null,
    val receiverAddress: String? = null,
    val payTime: String? = null,
    val deliveryTime: String? = null,
    val receiveTime: String? = null,
    val createTime: String? = null
) {
    companion object {
        const val STATUS_UNPAID = 0      // 待付款
        const val STATUS_PAID = 1        // 待发货
        const val STATUS_SHIPPED = 2     // 待收货
        const val STATUS_COMPLETED = 3   // 已完成
        const val STATUS_CANCELLED = 4   // 已取消
    }

    val statusText: String
        get() = when (status) {
            STATUS_UNPAID -> "待付款"
            STATUS_PAID -> "待发货"
            STATUS_SHIPPED -> "待收货"
            STATUS_COMPLETED -> "已完成"
            STATUS_CANCELLED -> "已取消"
            else -> "未知"
        }
}

/**
 * 订单项，对应后端 OrderItem
 */
@Serializable
data class OrderItem(
    val id: Long = 0,
    val orderId: Long = 0,
    val productId: Long = 0,
    val productName: String = "",
    val productImage: String? = null,
    val price: Double = 0.0,
    val quantity: Int = 0,
    val totalAmount: Double = 0.0,
    val createTime: String? = null
)

/**
 * 订单视图对象，对应后端 OrderVO（包含订单项列表）
 */
@Serializable
data class OrderVO(
    val id: Long = 0,
    val orderNo: String = "",
    val userId: Long = 0,
    val sellerId: Long = 0,
    val totalAmount: Double = 0.0,
    val payAmount: Double = 0.0,
    val freightAmount: Double = 0.0,
    val status: Int = 0,
    val receiverName: String? = null,
    val receiverPhone: String? = null,
    val receiverAddress: String? = null,
    val payTime: String? = null,
    val deliveryTime: String? = null,
    val receiveTime: String? = null,
    val createTime: String? = null,
    val items: List<OrderItem> = emptyList()
) {
    val statusText: String
        get() = when (status) {
            Order.STATUS_UNPAID -> "待付款"
            Order.STATUS_PAID -> "待发货"
            Order.STATUS_SHIPPED -> "待收货"
            Order.STATUS_COMPLETED -> "已完成"
            Order.STATUS_CANCELLED -> "已取消"
            else -> "未知"
        }
}
