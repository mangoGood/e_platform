package com.ecommerce.seller.feature.order

import com.ecommerce.core.model.OrderVO
import com.ecommerce.core.model.PageResult
import com.ecommerce.core.network.OrderApi
import com.ecommerce.core.network.apiCall
import com.ecommerce.core.network.requireData
import com.ecommerce.core.network.requireSuccess
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 卖家订单仓库。
 *
 * ## 改造要点
 * - 发货端点由 `order/deliver/{orderId}` 迁移到 **`order/{orderId}/ship`**。
 * - 删除本地重复的 `requireData()`，改用 shared-core 的统一扩展。
 */
@Singleton
class SellerOrderRepository @Inject constructor(
    private val orderApi: OrderApi
) {

    /**
     * 分页拉取当前卖家的订单。
     *
     * @param current 页码，从 1 开始
     * @param size    每页条数
     */
    suspend fun getMyOrders(
        current: Int = 1,
        size: Int = 10
    ): Result<PageResult<OrderVO>> = apiCall {
        orderApi.getOrdersBySellerId(current, size).requireData()
    }

    /**
     * 发货。
     *
     * 端点已迁移到 `POST order/{orderId}/ship`（原 `order/deliver/{orderId}`）。
     * 返回体为空，用 [requireSuccess] 而不是 requireData。
     *
     * @param orderId 订单 id
     */
    suspend fun deliver(orderId: Long): Result<Unit> = apiCall {
        orderApi.shipOrder(orderId).requireSuccess()
    }

    /**
     * 拉取单个订单详情。
     *
     * 卖家侧没有独立的详情端点，复用 `GET order/{orderId}`。
     *
     * @param orderId 订单 id
     */
    suspend fun getOrderDetail(orderId: Long): Result<OrderVO> = apiCall {
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
}
