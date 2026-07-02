package com.ecommerce.seller.feature.order

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ecommerce.core.model.Order
import com.ecommerce.core.model.OrderVO
import com.ecommerce.core.model.PageResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OrderListUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isDelivering: Boolean = false,
    val orders: List<OrderVO> = emptyList(),
    val current: Long = 1,
    val pages: Long = 1,
    val total: Long = 0,
    val errorMessage: String? = null,
    val actionSuccess: String? = null
)

@HiltViewModel
class OrderListViewModel @Inject constructor(
    private val repository: SellerOrderRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(OrderListUiState())
    val uiState: StateFlow<OrderListUiState> = _uiState.asStateFlow()

    init { loadOrders() }

    fun loadOrders(current: Long = 1) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = current == 1L,
                    errorMessage = null,
                    current = current
                )
            }
            repository.getMyOrders(current.toInt(), size = 10)
                .onSuccess { page ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            orders = page.records,
                            current = page.current,
                            pages = page.pages,
                            total = page.total
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = e.message ?: "加载失败"
                        )
                    }
                }
        }
    }

    fun refresh() {
        _uiState.update { it.copy(isRefreshing = true) }
        loadOrders(1)
    }

    fun loadMore() {
        val s = _uiState.value
        if (s.isLoading || s.current >= s.pages) return
        loadOrders(s.current + 1)
    }

    fun deliver(orderId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isDelivering = true) }
            repository.deliver(orderId)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isDelivering = false,
                            orders = it.orders.map { o ->
                                if (o.id == orderId) o.copy(status = Order.STATUS_SHIPPED) else o
                            },
                            actionSuccess = "发货成功"
                        )
                    }
                }
                .onFailure { e ->
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
