package com.ecommerce.seller.navigation

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val PRODUCTS = "products"
    const val ORDERS = "orders"
    const val PROFILE = "profile"
    const val PRODUCT_EDIT = "productEdit/{productId}"
    const val ORDER_DETAIL = "orderDetail/{orderId}"

    fun productEdit(productId: Long = 0L) = "productEdit/$productId"
    fun orderDetail(orderId: Long) = "orderDetail/$orderId"
}
