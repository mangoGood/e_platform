package com.ecommerce.buyer.feature.product

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ecommerce.core.model.Product
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProductDetailUiState(
    val isLoading: Boolean = true,
    val product: Product? = null,
    val quantity: Int = 1,
    val errorMessage: String? = null,
    val addToCartSuccess: Boolean = false
)

@HiltViewModel
class ProductDetailViewModel @Inject constructor(
    private val repository: ProductDetailRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProductDetailUiState())
    val uiState: StateFlow<ProductDetailUiState> = _uiState.asStateFlow()

    fun loadProduct(productId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            repository.getProductById(productId)
                .onSuccess { product ->
                    _uiState.update {
                        it.copy(isLoading = false, product = product, errorMessage = null)
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = e.message ?: "加载失败")
                    }
                }
        }
    }

    fun changeQuantity(delta: Int) {
        _uiState.update {
            val newQty = (it.quantity + delta).coerceAtLeast(1)
            it.copy(quantity = newQty)
        }
    }

    fun addToCart() {
        val current = _uiState.value
        val product = current.product ?: return
        viewModelScope.launch {
            repository.addToCart(product.id, current.quantity)
                .onSuccess {
                    _uiState.update { it.copy(addToCartSuccess = true) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(errorMessage = e.message ?: "加入购物车失败") }
                }
        }
    }

    fun consumeAddToCartSuccess() {
        _uiState.update { it.copy(addToCartSuccess = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
