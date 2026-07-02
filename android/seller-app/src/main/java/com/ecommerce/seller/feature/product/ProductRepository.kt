package com.ecommerce.seller.feature.product

import com.ecommerce.core.datastore.TokenManager
import com.ecommerce.core.model.ApiResponse
import com.ecommerce.core.model.Product
import com.ecommerce.core.model.ProductRequest
import com.ecommerce.core.network.ProductApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProductRepository @Inject constructor(
    private val productApi: ProductApi,
    private val tokenManager: TokenManager
) {
    private val sellerId: Long get() = tokenManager.userId

    suspend fun getMyProducts(): Result<List<Product>> = runCatching {
        productApi.getProductsBySellerId(sellerId).requireData()
    }

    suspend fun getProduct(id: Long): Result<Product> = runCatching {
        productApi.getProductById(id).requireData()
    }

    suspend fun addProduct(request: ProductRequest): Result<Unit> = runCatching {
        productApi.addProduct(request).requireData()
    }

    suspend fun updateProduct(id: Long, request: ProductRequest): Result<Unit> = runCatching {
        productApi.updateProduct(id, request).requireData()
    }

    suspend fun deleteProduct(id: Long): Result<Unit> = runCatching {
        productApi.deleteProduct(id).requireData()
    }

    private fun <T> ApiResponse<T>.requireData(): T {
        if (code != 200) throw RuntimeException(message ?: "请求失败")
        return data ?: throw RuntimeException("数据为空")
    }
}
