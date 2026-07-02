package com.ecommerce.buyer.feature.cart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CartUiState(
    val isLoading: Boolean = true,
    val items: List<CartItem> = emptyList(),
    val errorMessage: String? = null,
    val allSelected: Boolean = true
) {
    val selectedItems: List<CartItem> get() = items.filter { it.selected }
    val totalCount: Int get() = selectedItems.sumOf { it.cart.quantity }
    val totalPrice: Double get() = selectedItems.sumOf { it.subtotal }
}

@HiltViewModel
class CartViewModel @Inject constructor(
    private val repository: CartRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CartUiState())
    val uiState: StateFlow<CartUiState> = _uiState.asStateFlow()

    init {
        loadCart()
    }

    fun loadCart() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            repository.getCartWithProducts()
                .onSuccess { items ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            items = items,
                            allSelected = items.isNotEmpty() && items.all { it.selected },
                            errorMessage = null
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = e.message ?: "加载失败")
                    }
                }
        }
    }

    fun toggleSelect(productId: Long) {
        _uiState.update { state ->
            val newItems = state.items.map { item ->
                if (item.product.id == productId) {
                    item.copy(cart = item.cart.copy(selected = if (item.selected) 0 else 1))
                } else item
            }
            state.copy(
                items = newItems,
                allSelected = newItems.isNotEmpty() && newItems.all { it.selected }
            )
        }
    }

    fun toggleSelectAll() {
        val target = if (_uiState.value.allSelected) 0 else 1
        _uiState.update { state ->
            val newItems = state.items.map { it.copy(cart = it.cart.copy(selected = target)) }
            state.copy(items = newItems, allSelected = target == 1)
        }
    }

    fun changeQuantity(productId: Long, delta: Int) {
        val current = _uiState.value.items.find { it.product.id == productId } ?: return
        val newQty = (current.cart.quantity + delta).coerceAtLeast(1)
        if (newQty == current.cart.quantity) return
        viewModelScope.launch {
            repository.updateQuantity(productId, newQty)
                .onSuccess {
                    _uiState.update { state ->
                        val newItems = state.items.map { item ->
                            if (item.product.id == productId) {
                                item.copy(cart = item.cart.copy(quantity = newQty))
                            } else item
                        }
                        state.copy(items = newItems)
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(errorMessage = e.message ?: "修改数量失败") }
                }
        }
    }

    fun remove(productId: Long) {
        viewModelScope.launch {
            repository.remove(productId)
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(items = state.items.filterNot { it.product.id == productId })
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(errorMessage = e.message ?: "删除失败") }
                }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
