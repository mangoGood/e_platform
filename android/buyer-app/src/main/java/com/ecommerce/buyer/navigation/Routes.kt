package com.ecommerce.buyer.navigation

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val CART = "cart"
    const val ORDERS = "orders"
    const val PROFILE = "profile"
    const val ADDRESSES = "addresses_manage"
    const val PRODUCT_DETAIL = "product/{id}"
    const val CHECKOUT = "checkout/{productIds}"
    const val ADDRESS_LIST = "addresses"
    const val ORDER_DETAIL = "orderDetail/{orderId}"

    fun productDetail(id: Long) = "product/$id"
    fun checkout(productIds: List<Long>) = "checkout/${productIds.joinToString(",")}"
    fun orderDetail(orderId: Long) = "orderDetail/$orderId"
}
