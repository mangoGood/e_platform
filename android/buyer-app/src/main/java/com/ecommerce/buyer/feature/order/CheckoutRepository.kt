package com.ecommerce.buyer.feature.order

import com.ecommerce.core.model.Address
import com.ecommerce.core.model.ApiResponse
import com.ecommerce.core.model.CreateOrderRequest
import com.ecommerce.core.model.OrderVO
import com.ecommerce.core.model.Product
import com.ecommerce.core.network.AddressApi
import com.ecommerce.core.network.OrderApi
import com.ecommerce.core.network.ProductApi
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

@Singleton
class CheckoutRepository @Inject constructor(
    private val productApi: ProductApi,
    private val addressApi: AddressApi,
    private val orderApi: OrderApi
) {
    /**
     * 加载结算商品列表（根据 productIds）
     */
    suspend fun loadCheckoutItems(productIds: List<Long>): Result<List<CheckoutItem>> = runCatching {
        if (productIds.isEmpty()) return@runCatching emptyList()
        val ids = productIds.joinToString(",")
        val products = productApi.getProductsByIds(ids).requireData()
        // 默认数量 1，实际数量由调用方传入（购物车场景）
        products.map { CheckoutItem(it, 1) }
    }

    /**
     * 加载用户地址列表
     */
    suspend fun loadAddresses(): Result<List<Address>> = runCatching {
        addressApi.getAddresses().requireData()
    }

    /**
     * 创建订单
     */
    suspend fun createOrder(
        items: List<CreateOrderRequest.OrderItemRequest>,
        address: Address
    ): Result<List<OrderVO>> = runCatching {
        val request = CreateOrderRequest(
            items = items,
            receiverName = address.receiverName,
            receiverPhone = address.receiverPhone,
            receiverAddress = address.fullAddress
        )
        orderApi.createOrder(request).requireData()
    }

    private fun <T> ApiResponse<T>.requireData(): T {
        if (code != 200) throw RuntimeException(message ?: "请求失败")
        return data ?: throw RuntimeException("数据为空")
    }
}
