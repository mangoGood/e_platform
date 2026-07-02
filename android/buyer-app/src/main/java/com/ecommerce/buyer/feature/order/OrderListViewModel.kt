package com.ecommerce.buyer.feature.order

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ecommerce.core.model.OrderVO
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
    val orders: List<OrderVO> = emptyList(),
    val currentPage: Long = 1,
    val totalPages: Long = 1,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
    val actionSuccess: String? = null
)

@HiltViewModel
class OrderListViewModel @Inject constructor(
    private val repository: OrderRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(OrderListUiState())
    val uiState: StateFlow<OrderListUiState> = _uiState.asStateFlow()

    init {
        loadFirstPage()
    }

    fun loadFirstPage() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            repository.getOrders(current = 1, size = 10)
                .onSuccess { page ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            orders = page.records,
                            currentPage = 1L,
                            totalPages = page.pages
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
        loadFirstPage()
    }

    fun loadMore() {
        val current = _uiState.value
        if (current.isLoadingMore || current.currentPage >= current.totalPages) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            val nextPage = current.currentPage + 1
            repository.getOrders(nextPage.toInt(), 10)
                .onSuccess { page ->
                    _uiState.update {
                        it.copy(
                            orders = it.orders + page.records,
                            currentPage = nextPage,
                            totalPages = page.pages,
                            isLoadingMore = false
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoadingMore = false, errorMessage = e.message ?: "加载更多失败")
                    }
                }
        }
    }

    fun payOrder(orderId: Long) {
        viewModelScope.launch {
            repository.payOrder(orderId)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            orders = it.orders.map { o ->
                                if (o.id == orderId) o.copy(status = OrderVO_STATUS_PAID) else o
                            },
                            actionSuccess = "支付成功"
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(errorMessage = e.message ?: "支付失败") }
                }
        }
    }

    fun cancelOrder(orderId: Long) {
        viewModelScope.launch {
            repository.cancelOrder(orderId)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            orders = it.orders.map { o ->
                                if (o.id == orderId) o.copy(status = OrderVO_STATUS_CANCELLED) else o
                            },
                            actionSuccess = "订单已取消"
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(errorMessage = e.message ?: "取消失败") }
                }
        }
    }

    fun receiveOrder(orderId: Long) {
        viewModelScope.launch {
            repository.receiveOrder(orderId)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            orders = it.orders.map { o ->
                                if (o.id == orderId) o.copy(status = OrderVO_STATUS_COMPLETED) else o
                            },
                            actionSuccess = "确认收货成功"
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(errorMessage = e.message ?: "确认收货失败") }
                }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, actionSuccess = null) }
    }

    companion object {
        private const val OrderVO_STATUS_PAID = 1
        private const val OrderVO_STATUS_CANCELLED = 4
        private const val OrderVO_STATUS_COMPLETED = 3
    }
}
