package com.ecommerce.buyer.feature.home

import com.ecommerce.core.model.PageResult
import com.ecommerce.core.model.Product
import com.ecommerce.core.network.ProductApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProductRepository @Inject constructor(
    private val productApi: ProductApi
) {
    /**
     * 分页查询商品列表
     */
    suspend fun getProductList(
        current: Int = 1,
        size: Int = 10,
        categoryId: Long? = null,
        keyword: String? = null
    ): Result<PageResult<Product>> {
        return try {
            val response = productApi.getProductList(current, size, categoryId, keyword)
            val data = response.data
            if (response.isSuccess && data != null) {
                Result.success(data)
            } else {
                Result.failure(RuntimeException(response.message ?: "获取商品列表失败"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取商品详情
     */
    suspend fun getProductDetail(id: Long): Result<Product> {
        return try {
            val response = productApi.getProductById(id)
            val data = response.data
            if (response.isSuccess && data != null) {
                Result.success(data)
            } else {
                Result.failure(RuntimeException(response.message ?: "获取商品详情失败"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
