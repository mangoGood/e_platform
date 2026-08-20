package com.ecommerce.buyer.feature.home

import com.ecommerce.core.model.PageResult
import com.ecommerce.core.model.Product
import com.ecommerce.core.network.ProductApi
import com.ecommerce.core.network.apiCall
import com.ecommerce.core.network.requireData
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 买家首页商品仓库。
 *
 * 改造要点：接口返回类型换成 `Response<ApiResponse<T>>` 后，
 * 原先手写的 `try/catch + response.isSuccess` 三段式全部由
 * [apiCall] + [requireData] 取代，失败时抛出的异常带后端中文文案。
 */
@Singleton
class ProductRepository @Inject constructor(
    private val productApi: ProductApi
) {

    /**
     * 分页查询商品列表。
     *
     * @param current    页码，从 1 开始
     * @param size       每页条数
     * @param categoryId 分类过滤，null 表示不过滤
     * @param keyword    关键字搜索，null 表示不过滤
     */
    suspend fun getProductList(
        current: Int = 1,
        size: Int = 10,
        categoryId: Long? = null,
        keyword: String? = null
    ): Result<PageResult<Product>> = apiCall {
        productApi.getProductList(current, size, categoryId, keyword).requireData()
    }

    /**
     * 获取商品详情。
     *
     * @param id 商品 id
     */
    suspend fun getProductDetail(id: Long): Result<Product> = apiCall {
        productApi.getProductById(id).requireData()
    }
}
