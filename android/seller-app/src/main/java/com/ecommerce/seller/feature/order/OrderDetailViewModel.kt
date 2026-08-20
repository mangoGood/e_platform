package com.ecommerce.seller.feature.order

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ecommerce.core.model.Order
import com.ecommerce.core.model.OrderVO
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OrderDetailUiState(
    val isLoading: Boolean = true,
    val isDelivering: Boolean = false,
    val order: OrderVO? = null,
    val errorMessage: String? = null,
    val actionSuccess: String? = null
)

/**
 * 卖家订单详情。
 *
 * ## 改造要点
 * 1. 不再直接注入 [com.ecommerce.core.network.OrderApi]，改走 [SellerOrderRepository]。
 *    原实现在 ViewModel 里裸调 Retrofit 接口并读 `resp.data`，
 *    接口切到 `Response<ApiResponse<T>>` 之后那种写法直接编译不过；
 *    而且错误处理散在 ViewModel 里，拿不到 ErrorMapper 翻译的中文文案。
 * 2. 详情由"拉 100 条列表再 find"改成直接 `GET order/{orderId}`。
 *    原写法在订单数超过 100 条后会静默查不到，报"订单不存在"。
 * 3. 发货端点迁移到 `order/{orderId}/ship`（在 Repository 里完成）。
 */
@HiltViewModel
class OrderDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SellerOrderRepository
) : ViewModel() {

    private val orderId: Long = savedStateHandle.get<Long>("orderId")
        ?: savedStateHandle.get<String>("orderId")?.toLongOrNull()
        ?: 0L

    private val _uiState = MutableStateFlow(OrderDetailUiState())
    val uiState: StateFlow<OrderDetailUiState> = _uiState.asStateFlow()

    init {
        loadOrder()
    }

    fun loadOrder() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            repository.getOrderDetail(orderId)
                .onSuccess { order ->
                    _uiState.update { it.copy(isLoading = false, order = order) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = e.message ?: "加载失败")
                    }
                }
        }
    }

    /**
     * 发货。成功后把状态推进到"待收货"，并静默拉一次真实数据补齐 `deliveryTime`。
     */
    fun deliver() {
        val current = _uiState.value.order ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isDelivering = true, errorMessage = null) }
            repository.deliver(current.id)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isDelivering = false,
                            order = current.copy(status = Order.STATUS_SHIPPED),
                            actionSuccess = "发货成功"
                        )
                    }
                    refreshQuietly()
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isDelivering = false, errorMessage = e.message ?: "发货失败")
                    }
                }
        }
    }

    /**
     * 静默刷新，不翻转 `isLoading`。
     */
    private fun refreshQuietly() {
        viewModelScope.launch {
            repository.getOrderDetail(orderId)
                .onSuccess { order -> _uiState.update { it.copy(order = order) } }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, actionSuccess = null) }
    }
}
