package com.ecommerce.seller.feature.product

import com.ecommerce.core.datastore.TokenManager
import com.ecommerce.core.model.Product
import com.ecommerce.core.model.ProductRequest
import com.ecommerce.core.network.ProductApi
import com.ecommerce.core.network.apiCall
import com.ecommerce.core.network.requireData
import com.ecommerce.core.network.requireSuccess
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 卖家商品仓库。
 *
 * 改造要点：删除本地重复的 `requireData()`；
 * 增删改这类空返回接口改用 [requireSuccess]（它们的 `data` 恒为 null）。
 */
@Singleton
class ProductRepository @Inject constructor(
    private val productApi: ProductApi,
    private val tokenManager: TokenManager
) {

    private val sellerId: Long get() = tokenManager.userId

    /** 当前卖家的全部商品 */
    suspend fun getMyProducts(): Result<List<Product>> = apiCall {
        productApi.getProductsBySellerId(sellerId).requireData()
    }

    /**
     * 单个商品详情。
     *
     * @param id 商品 id
     */
    suspend fun getProduct(id: Long): Result<Product> = apiCall {
        productApi.getProductById(id).requireData()
    }

    /**
     * 新增商品。
     *
     * @param request 商品表单
     */
    suspend fun addProduct(request: ProductRequest): Result<Unit> = apiCall {
        productApi.addProduct(request).requireSuccess()
    }

    /**
     * 更新商品。
     *
     * @param id      商品 id
     * @param request 商品表单
     */
    suspend fun updateProduct(id: Long, request: ProductRequest): Result<Unit> = apiCall {
        productApi.updateProduct(id, request).requireSuccess()
    }

    /**
     * 删除商品。
     *
     * @param id 商品 id
     */
    suspend fun deleteProduct(id: Long): Result<Unit> = apiCall {
        productApi.deleteProduct(id).requireSuccess()
    }
}
