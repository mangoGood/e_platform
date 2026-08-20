package com.ecommerce.buyer.feature.order

import com.ecommerce.core.model.OrderVO
import com.ecommerce.core.model.PageResult
import com.ecommerce.core.network.OrderApi
import com.ecommerce.core.network.apiCall
import com.ecommerce.core.network.requireData
import com.ecommerce.core.network.requireSuccess
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 买家订单仓库。
 *
 * ## 改造要点
 * - 确认收货端点由 `order/receive/{orderId}` 迁移到 **`order/{orderId}/confirm`**。
 * - 本地那份 `private fun ApiResponse<T>.requireData()` 已删除，
 *   统一使用 `com.ecommerce.core.network` 里的扩展（全工程 6 份重复实现收敛为 1 份）。
 * - 空返回接口（支付/取消/确认）用 [requireSuccess]，不用 [requireData]——
 *   它们的 `data` 恒为 null，用 requireData 会误判成"数据为空"而报错。
 */
@Singleton
class OrderRepository @Inject constructor(
    private val orderApi: OrderApi
) {

    /**
     * 分页拉取当前买家的订单。
     *
     * @param current 页码，从 1 开始
     * @param size    每页条数
     */
    suspend fun getOrders(current: Int, size: Int): Result<PageResult<OrderVO>> = apiCall {
        orderApi.getOrdersByUserId(current, size).requireData()
    }

    /**
     * 拉取订单详情：订单主体 + 订单项两次请求合并成一个 [OrderVO]。
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

    /** 支付订单 */
    suspend fun payOrder(orderId: Long): Result<Unit> = apiCall {
        orderApi.payOrder(orderId).requireSuccess()
    }

    /** 取消订单 */
    suspend fun cancelOrder(orderId: Long): Result<Unit> = apiCall {
        orderApi.cancelOrder(orderId).requireSuccess()
    }

    /**
     * 确认收货。
     *
     * 端点已迁移到 `POST order/{orderId}/confirm`（原 `order/receive/{orderId}`）。
     * 方法名保留 `receiveOrder` 不改，避免牵动 ViewModel 与 UI 层的调用点。
     */
    suspend fun receiveOrder(orderId: Long): Result<Unit> = apiCall {
        orderApi.confirmOrder(orderId).requireSuccess()
    }
}
