package com.ecommerce.buyer.feature.order

import com.ecommerce.core.model.Address
import com.ecommerce.core.model.CreateOrderRequest
import com.ecommerce.core.model.OrderVO
import com.ecommerce.core.model.Product
import com.ecommerce.core.network.AddressApi
import com.ecommerce.core.network.OrderApi
import com.ecommerce.core.network.ProductApi
import com.ecommerce.core.network.QueueInterceptor
import com.ecommerce.core.network.apiCall
import com.ecommerce.core.network.requireData
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 结算页商品项（包含商品和数量）
 */
data class CheckoutItem(
    val product: Product,
    val quantity: Int
) {
    val subtotal: Double get() = product.price * quantity
}

/**
 * 下单结算仓库。
 *
 * 改造要点：删除本地重复的 `requireData()`；下单的秒杀排队（HTTP 202）
 * 由 [QueueInterceptor] 在网络层透明处理，本层**不需要**任何排队相关代码。
 */
@Singleton
class CheckoutRepository @Inject constructor(
    private val productApi: ProductApi,
    private val addressApi: AddressApi,
    private val orderApi: OrderApi
) {

    /**
     * 加载结算商品列表。
     *
     * @param productIds 商品 id 列表
     */
    suspend fun loadCheckoutItems(productIds: List<Long>): Result<List<CheckoutItem>> = apiCall {
        if (productIds.isEmpty()) {
            return@apiCall emptyList()
        }
        val ids = productIds.joinToString(",")
        val products = productApi.getProductsByIds(ids).requireData()
        // 默认数量 1，实际数量由调用方传入（购物车场景）
        products.map { CheckoutItem(it, 1) }
    }

    /**
     * 加载用户收货地址列表。
     */
    suspend fun loadAddresses(): Result<List<Address>> = apiCall {
        addressApi.getAddresses().requireData()
    }

    /**
     * 创建订单。
     *
     * 网关对本端点有秒杀排队保护，可能先返回 HTTP 202 让客户端排队。
     * 该分支完全由 [QueueInterceptor] 消化：它会按后端给的间隔轮询
     * `GET queue/status`，就绪后带 `X-Queue-Token` 自动重发本请求。
     * 所以从这里看，下单要么成功要么抛带中文文案的异常，**没有中间态**。
     *
     * @param items   下单商品项
     * @param address 收货地址
     */
    suspend fun createOrder(
        items: List<CreateOrderRequest.OrderItemRequest>,
        address: Address
    ): Result<List<OrderVO>> = apiCall {
        val request = CreateOrderRequest(
            items = items,
            receiverName = address.receiverName,
            receiverPhone = address.receiverPhone,
            receiverAddress = address.fullAddress
        )
        orderApi.createOrder(request).requireData()
    }
}
