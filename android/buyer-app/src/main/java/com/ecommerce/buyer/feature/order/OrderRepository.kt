package com.ecommerce.buyer.feature.order

import com.ecommerce.core.model.ApiResponse
import com.ecommerce.core.model.OrderVO
import com.ecommerce.core.model.PageResult
import com.ecommerce.core.network.OrderApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OrderRepository @Inject constructor(
    private val orderApi: OrderApi
) {
    suspend fun getOrders(current: Int, size: Int): Result<PageResult<OrderVO>> = runCatching {
        orderApi.getOrdersByUserId(current, size).requireData()
    }

    suspend fun getOrderDetail(orderId: Long): Result<OrderVO> = runCatching {
        val order = orderApi.getOrderById(orderId).requireData()
        val items = orderApi.getOrderItems(orderId).requireData()
        OrderVO(
            id = order.id,
            orderNo = order.orderNo,
            userId = order.userId,
            sellerId = order.sellerId,
            totalAmount = order.totalAmount,
            payAmount = order.payAmount,
            freightAmount = order.freightAmount,
            status = order.status,
            receiverName = order.receiverName,
            receiverPhone = order.receiverPhone,
            receiverAddress = order.receiverAddress,
            payTime = order.payTime,
            deliveryTime = order.deliveryTime,
            receiveTime = order.receiveTime,
            createTime = order.createTime,
            items = items
        )
    }

    suspend fun payOrder(orderId: Long): Result<Unit> = runCatching {
        orderApi.payOrder(orderId).requireData()
    }

    suspend fun cancelOrder(orderId: Long): Result<Unit> = runCatching {
        orderApi.cancelOrder(orderId).requireData()
    }

    suspend fun receiveOrder(orderId: Long): Result<Unit> = runCatching {
        orderApi.receiveOrder(orderId).requireData()
    }

    private fun <T> ApiResponse<T>.requireData(): T {
        if (code != 200) throw RuntimeException(message ?: "请求失败")
        return data ?: throw RuntimeException("数据为空")
    }
}
