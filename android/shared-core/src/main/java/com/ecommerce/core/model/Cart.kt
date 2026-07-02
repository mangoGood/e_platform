package com.ecommerce.core.model

import kotlinx.serialization.Serializable

/**
 * 购物车项，对应后端 com.ecommerce.order.entity.Cart
 */
@Serializable
data class Cart(
    val id: Long = 0,
    val userId: Long = 0,
    val productId: Long = 0,
    val quantity: Int = 0,
    val selected: Int = 1,
    val createTime: String? = null,
    val updateTime: String? = null
)

/**
 * 收货地址，对应后端 com.ecommerce.order.entity.Address
 */
@Serializable
data class Address(
    val id: Long = 0,
    val userId: Long = 0,
    val receiverName: String = "",
    val receiverPhone: String = "",
    val province: String = "",
    val city: String = "",
    val district: String = "",
    val detailAddress: String = "",
    val isDefault: Int = 0,
    val createTime: String? = null
) {
    /** 完整地址文本 */
    val fullAddress: String
        get() = "$province$city$district$detailAddress"
}

/**
 * 下单请求，对应后端 CreateOrderRequest
 */
@Serializable
data class CreateOrderRequest(
    val items: List<OrderItemRequest>,
    val receiverName: String? = null,
    val receiverPhone: String? = null,
    val receiverAddress: String? = null
) {
    @Serializable
    data class OrderItemRequest(
        val productId: Long,
        val quantity: Int
    )
}
