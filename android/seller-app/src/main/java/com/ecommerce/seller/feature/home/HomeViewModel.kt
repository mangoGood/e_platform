package com.ecommerce.seller.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ecommerce.core.datastore.TokenManager
import com.ecommerce.core.model.Order
import com.ecommerce.core.network.ErrorMapper
import com.ecommerce.core.network.OrderApi
import com.ecommerce.core.network.ProductApi
import com.ecommerce.core.network.dataOrNull
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
    val orderCount: Long = 0L,
    val pendingShipCount: Int = 0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

/**
 * 卖家工作台首页。
 *
 * 改造要点：接口切到 `Response<ApiResponse<T>>` 后，原先的 `resp.data` 不再存在，
 * 改用 [dataOrNull] 扩展解包；异常统一过 [ErrorMapper] 拿中文文案。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val productApi: ProductApi,
    private val orderApi: OrderApi,
    private val tokenManager: TokenManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (!tokenManager.isLoggedIn) {
            _uiState.update {
                it.copy(isLoggedIn = false, username = null, isLoading = false)
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
                val products = productApi.getProductsBySellerId(sellerId).dataOrNull().orEmpty()
                val ordersPage = orderApi.getOrdersBySellerId(current = 1, size = STAT_PAGE_SIZE)
                    .dataOrNull()
                val orders = ordersPage?.records.orEmpty()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        productCount = products.size,
                        // 总数取分页的 total 而不是 records.size：
                        // 后者最多只有一页，订单超过 STAT_PAGE_SIZE 条后统计值就不准了
                        orderCount = ordersPage?.total ?: orders.size.toLong(),
                        pendingShipCount = orders.count { order -> order.status == Order.STATUS_PAID }
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = ErrorMapper.toApiException(e).message
                    )
                }
            }
        }
    }

    private companion object {
        /** 首页统计用的抽样页大小；"待发货"计数基于这一页，总数用 PageResult.total */
        const val STAT_PAGE_SIZE = 100
    }
}
