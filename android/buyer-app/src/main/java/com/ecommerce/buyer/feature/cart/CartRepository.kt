package com.ecommerce.buyer.feature.cart

import com.ecommerce.core.model.Cart
import com.ecommerce.core.model.Product
import com.ecommerce.core.network.CartApi
import com.ecommerce.core.network.ProductApi
import com.ecommerce.core.network.apiCall
import com.ecommerce.core.network.requireData
import com.ecommerce.core.network.requireSuccess
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

/**
 * 购物车仓库。
 *
 * 改造要点：删除本地重复的 `requireData()`，统一走 shared-core 扩展；
 * 增删改这类空返回接口改用 [requireSuccess]。
 */
@Singleton
class CartRepository @Inject constructor(
    private val cartApi: CartApi,
    private val productApi: ProductApi
) {

    /**
     * 拉取购物车并批量补齐商品信息。
     *
     * 购物车为空时直接短路，避免用空 ids 去打 `product/batch` 拿 400。
     */
    suspend fun getCartWithProducts(): Result<List<CartItem>> = apiCall {
        val carts = cartApi.getCart().requireData()
        if (carts.isEmpty()) {
            return@apiCall emptyList()
        }
        val ids = carts.joinToString(",") { it.productId.toString() }
        val products = productApi.getProductsByIds(ids).requireData()
        val productMap = products.associateBy { it.id }
        // 商品可能已下架被后端过滤掉，mapNotNull 丢弃这类脏数据而不是让整页崩掉
        carts.mapNotNull { cart ->
            productMap[cart.productId]?.let { CartItem(cart, it) }
        }
    }

    /**
     * 修改购物车中某商品的数量。
     *
     * @param productId 商品 id
     * @param quantity  新数量
     */
    suspend fun updateQuantity(productId: Long, quantity: Int): Result<Unit> = apiCall {
        cartApi.updateCartQuantity(productId, quantity).requireSuccess()
    }

    /** 从购物车移除某商品 */
    suspend fun remove(productId: Long): Result<Unit> = apiCall {
        cartApi.removeFromCart(productId).requireSuccess()
    }

    /** 清空购物车 */
    suspend fun clear(): Result<Unit> = apiCall {
        cartApi.clearCart().requireSuccess()
    }
}
