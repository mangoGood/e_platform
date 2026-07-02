package com.ecommerce.buyer.feature.order

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
    val order: OrderVO? = null,
    val errorMessage: String? = null,
    val actionSuccess: String? = null,
    val isProcessing: Boolean = false
)

@HiltViewModel
class OrderDetailViewModel @Inject constructor(
    private val repository: OrderRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val orderId: Long = savedStateHandle.get<String>("orderId")?.toLongOrNull() ?: 0L

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

    fun payOrder() {
        updateStatus { repository.payOrder(orderId) }
    }

    fun cancelOrder() {
        updateStatus { repository.cancelOrder(orderId) }
    }

    fun receiveOrder() {
        updateStatus { repository.receiveOrder(orderId) }
    }

    private fun updateStatus(action: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, errorMessage = null) }
            action()
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            isProcessing = false,
                            order = state.order?.copy(
                                status = when (state.order.status) {
                                    Order.STATUS_UNPAID -> Order.STATUS_PAID
                                    else -> state.order.status
                                }
                            ),
                            actionSuccess = "操作成功"
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isProcessing = false, errorMessage = e.message ?: "操作失败")
                    }
                }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, actionSuccess = null) }
    }
}
