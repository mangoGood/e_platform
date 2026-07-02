package com.ecommerce.buyer.feature.order

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ecommerce.core.model.Address
import com.ecommerce.core.model.CreateOrderRequest
import com.ecommerce.core.model.OrderVO
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CheckoutUiState(
    val isLoading: Boolean = true,
    val isSubmitting: Boolean = false,
    val items: List<CheckoutItem> = emptyList(),
    val addresses: List<Address> = emptyList(),
    val selectedAddress: Address? = null,
    val errorMessage: String? = null,
    val createdOrders: List<OrderVO>? = null
) {
    val totalAmount: Double get() = items.sumOf { it.subtotal }
    val totalQuantity: Int get() = items.sumOf { it.quantity }
}

@HiltViewModel
class CheckoutViewModel @Inject constructor(
    private val repository: CheckoutRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    // 从导航参数获取 productIds（逗号分隔）
    private val productIds: List<Long> = savedStateHandle
        .get<String>("productIds")
        ?.split(",")
        ?.mapNotNull { it.toLongOrNull() }
        ?: emptyList()

    // 数量映射（productId -> quantity），默认 1
    private val quantities: MutableMap<Long, Int> = mutableMapOf()

    private val _uiState = MutableStateFlow(CheckoutUiState())
    val uiState: StateFlow<CheckoutUiState> = _uiState.asStateFlow()

    init {
        loadAll()
    }

    private fun loadAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val itemsResult = repository.loadCheckoutItems(productIds)
            val addressesResult = repository.loadAddresses()

            val items = itemsResult.getOrElse { e ->
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "加载失败") }
                return@launch
            }
            // 初始化数量
            items.forEach { quantities[it.product.id] = it.quantity }

            val addresses = addressesResult.getOrElse { emptyList() }
            val defaultAddr = addresses.find { it.isDefault == 1 } ?: addresses.firstOrNull()

            _uiState.update {
                it.copy(
                    isLoading = false,
                    items = items,
                    addresses = addresses,
                    selectedAddress = defaultAddr
                )
            }
        }
    }

    fun selectAddress(address: Address) {
        _uiState.update { it.copy(selectedAddress = address) }
    }

    fun submitOrder() {
        val current = _uiState.value
        val address = current.selectedAddress
        if (address == null) {
            _uiState.update { it.copy(errorMessage = "请选择收货地址") }
            return
        }
        if (current.items.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "没有可结算的商品") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            val orderItems = current.items.map {
                CreateOrderRequest.OrderItemRequest(
                    productId = it.product.id,
                    quantity = quantities[it.product.id] ?: it.quantity
                )
            }
            repository.createOrder(orderItems, address)
                .onSuccess { orders ->
                    _uiState.update { it.copy(isSubmitting = false, createdOrders = orders) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isSubmitting = false, errorMessage = e.message ?: "下单失败")
                    }
                }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
