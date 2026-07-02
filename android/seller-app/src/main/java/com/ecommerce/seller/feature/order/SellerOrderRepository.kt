package com.ecommerce.seller.feature.order

import com.ecommerce.core.model.ApiResponse
import com.ecommerce.core.model.OrderVO
import com.ecommerce.core.model.PageResult
import com.ecommerce.core.network.OrderApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SellerOrderRepository @Inject constructor(
    private val orderApi: OrderApi
) {
    suspend fun getMyOrders(
        current: Int = 1,
        size: Int = 10
    ): Result<PageResult<OrderVO>> = runCatching {
        orderApi.getOrdersBySellerId(current, size).requireData()
    }

    suspend fun deliver(orderId: Long): Result<Unit> = runCatching {
        orderApi.deliverOrder(orderId).requireData()
    }

    private fun <T> ApiResponse<T>.requireData(): T {
        if (code != 200) throw RuntimeException(message ?: "请求失败")
        return data ?: throw RuntimeException("数据为空")
    }
}
