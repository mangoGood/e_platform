package com.ecommerce.buyer.feature.product

import com.ecommerce.core.model.ApiResponse
import com.ecommerce.core.model.Product
import com.ecommerce.core.network.CartApi
import com.ecommerce.core.network.ProductApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProductDetailRepository @Inject constructor(
    private val productApi: ProductApi,
    private val cartApi: CartApi
) {
    suspend fun getProductById(id: Long): Result<Product> = runCatching {
        productApi.getProductById(id).requireData()
    }

    suspend fun addToCart(productId: Long, quantity: Int): Result<Unit> = runCatching {
        cartApi.addToCart(productId, quantity).requireData()
    }

    private fun <T> ApiResponse<T>.requireData(): T {
        if (code != 200) throw RuntimeException(message ?: "请求失败")
        return data ?: throw RuntimeException("数据为空")
    }
}
