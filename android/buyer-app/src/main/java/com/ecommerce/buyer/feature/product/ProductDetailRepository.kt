package com.ecommerce.buyer.feature.product

import com.ecommerce.core.model.Product
import com.ecommerce.core.network.CartApi
import com.ecommerce.core.network.ProductApi
import com.ecommerce.core.network.apiCall
import com.ecommerce.core.network.requireData
import com.ecommerce.core.network.requireSuccess
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 商品详情仓库（只负责商品本体与购物车）。
 *
 * 评论相关能力**刻意不并进来**，单独放在 [CommentRepository]：
 * 评论有自己的分页、权限上下文和三种写操作，混在一起这个类会迅速膨胀。
 */
@Singleton
class ProductDetailRepository @Inject constructor(
    private val productApi: ProductApi,
    private val cartApi: CartApi
) {

    /**
     * 获取商品详情。
     *
     * @param id 商品 id
     */
    suspend fun getProductById(id: Long): Result<Product> = apiCall {
        productApi.getProductById(id).requireData()
    }

    /**
     * 加入购物车。
     *
     * @param productId 商品 id
     * @param quantity  数量
     */
    suspend fun addToCart(productId: Long, quantity: Int): Result<Unit> = apiCall {
        cartApi.addToCart(productId, quantity).requireSuccess()
    }
}
