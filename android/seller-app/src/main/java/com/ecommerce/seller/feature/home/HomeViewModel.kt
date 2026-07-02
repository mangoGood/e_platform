package com.ecommerce.seller.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ecommerce.core.datastore.TokenManager
import com.ecommerce.core.model.ApiResponse
import com.ecommerce.core.model.Order
import com.ecommerce.core.model.OrderVO
import com.ecommerce.core.model.PageResult
import com.ecommerce.core.model.Product
import com.ecommerce.core.network.OrderApi
import com.ecommerce.core.network.ProductApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val isLoggedIn: Boolean = false,
    val username: String? = null,
    val productCount: Int = 0,
    val orderCount: Int = 0,
    val pendingShipCount: Int = 0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val productApi: ProductApi,
    private val orderApi: OrderApi,
    private val tokenManager: TokenManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (!tokenManager.isLoggedIn) {
            _uiState.update {
                it.copy(
                    isLoggedIn = false,
                    username = null,
                    isLoading = false
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                isLoggedIn = true,
                username = tokenManager.username,
                isLoading = true,
                errorMessage = null
            )
        }
        viewModelScope.launch {
            try {
                val sellerId = tokenManager.userId
                val productsResp = productApi.getProductsBySellerId(sellerId)
                val ordersResp = orderApi.getOrdersBySellerId(current = 1, size = 100)
                val products = productsResp.data ?: emptyList()
                val ordersPage = ordersResp.data
                val orders = ordersPage?.records ?: emptyList()
                val pendingShip = orders.count { it.status == Order.STATUS_PAID }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        productCount = products.size,
                        orderCount = orders.size,
                        pendingShipCount = pendingShip
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = e.message ?: "加载失败")
                }
            }
        }
    }
}
