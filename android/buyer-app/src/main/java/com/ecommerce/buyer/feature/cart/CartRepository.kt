package com.ecommerce.buyer.feature.cart

import com.ecommerce.core.model.ApiResponse
import com.ecommerce.core.model.Cart
import com.ecommerce.core.model.Product
import com.ecommerce.core.network.CartApi
import com.ecommerce.core.network.ProductApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 购物车项与商品信息的合并视图
 */
data class CartItem(
    val cart: Cart,
    val product: Product
) {
    val selected: Boolean get() = cart.selected == 1
    val subtotal: Double get() = product.price * cart.quantity
}

@Singleton
class CartRepository @Inject constructor(
    private val cartApi: CartApi,
    private val productApi: ProductApi
) {
    suspend fun getCartWithProducts(): Result<List<CartItem>> = runCatching {
        val carts = cartApi.getCart().requireData()
        if (carts.isEmpty()) return@runCatching emptyList()
        val ids = carts.joinToString(",") { it.productId.toString() }
        val products = productApi.getProductsByIds(ids).requireData()
        val productMap = products.associateBy { it.id }
        carts.mapNotNull { c ->
            productMap[c.productId]?.let { CartItem(c, it) }
        }
    }

    suspend fun updateQuantity(productId: Long, quantity: Int): Result<Unit> = runCatching {
        cartApi.updateCartQuantity(productId, quantity).requireData()
    }

    suspend fun remove(productId: Long): Result<Unit> = runCatching {
        cartApi.removeFromCart(productId).requireData()
    }

    suspend fun clear(): Result<Unit> = runCatching {
        cartApi.clearCart().requireData()
    }

    private fun <T> ApiResponse<T>.requireData(): T {
        if (code != 200) throw RuntimeException(message ?: "请求失败")
        return data ?: throw RuntimeException("数据为空")
    }
}
