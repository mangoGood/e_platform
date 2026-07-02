package com.ecommerce.seller.feature.order

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ecommerce.core.model.ApiResponse
import com.ecommerce.core.model.Order
import com.ecommerce.core.model.OrderVO
import com.ecommerce.core.network.OrderApi
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

@HiltViewModel
class OrderDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val orderApi: OrderApi
) : ViewModel() {

    private val orderId: Long = savedStateHandle.get<Long>("orderId") ?: 0L

    private val _uiState = MutableStateFlow(OrderDetailUiState())
    val uiState: StateFlow<OrderDetailUiState> = _uiState.asStateFlow()

    init { loadOrder() }

    fun loadOrder() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val resp = orderApi.getOrdersBySellerId(current = 1, size = 100)
                val order = resp.data?.records?.find { it.id == orderId }
                if (order != null) {
                    _uiState.update { it.copy(isLoading = false, order = order) }
                } else {
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = "订单不存在")
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = e.message ?: "加载失败")
                }
            }
        }
    }

    fun deliver() {
        val current = _uiState.value.order ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isDelivering = true) }
            try {
                orderApi.deliverOrder(current.id)
                _uiState.update {
                    it.copy(
                        isDelivering = false,
                        order = current.copy(status = Order.STATUS_SHIPPED),
                        actionSuccess = "发货成功"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isDelivering = false,
                        errorMessage = e.message ?: "发货失败"
                    )
                }
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, actionSuccess = null) }
    }
}
