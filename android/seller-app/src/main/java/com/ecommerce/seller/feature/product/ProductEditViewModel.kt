package com.ecommerce.seller.feature.product

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ecommerce.core.model.ProductRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProductEditUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isEdit: Boolean = false,
    val categoryId: String = "",
    val name: String = "",
    val description: String = "",
    val price: String = "",
    val originalPrice: String = "",
    val stock: String = "",
    val mainImage: String = "",
    val errorMessage: String? = null,
    val saveSuccess: Boolean = false
)

@HiltViewModel
class ProductEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ProductRepository
) : ViewModel() {

    private val productId: Long = savedStateHandle.get<Long>("productId") ?: 0L

    private val _uiState = MutableStateFlow(ProductEditUiState(isEdit = productId > 0L))
    val uiState: StateFlow<ProductEditUiState> = _uiState.asStateFlow()

    init {
        if (productId > 0L) loadProduct()
    }

    private fun loadProduct() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repository.getProduct(productId)
                .onSuccess { p ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            categoryId = p.categoryId.toString(),
                            name = p.name,
                            description = p.description ?: "",
                            price = p.price.toString(),
                            originalPrice = p.originalPrice?.toString() ?: "",
                            stock = p.stock.toString(),
                            mainImage = p.mainImage ?: ""
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

    fun onCategoryIdChange(v: String) = _uiState.update { it.copy(categoryId = v.filter { c -> c.isDigit() }) }
    fun onNameChange(v: String) = _uiState.update { it.copy(name = v) }
    fun onDescriptionChange(v: String) = _uiState.update { it.copy(description = v) }
    fun onPriceChange(v: String) = _uiState.update { it.copy(price = v.filterNum()) }
    fun onOriginalPriceChange(v: String) = _uiState.update { it.copy(originalPrice = v.filterNum()) }
    fun onStockChange(v: String) = _uiState.update { it.copy(stock = v.filter { c -> c.isDigit() }) }
    fun onMainImageChange(v: String) = _uiState.update { it.copy(mainImage = v) }

    private fun String.filterNum(): String {
        val v = filter { it.isDigit() || it == '.' }
        // 只保留第一个小数点
        val firstDot = v.indexOf('.')
        return if (firstDot >= 0) {
            v.substring(0, firstDot + 1) + v.substring(firstDot + 1).replace(".", "")
        } else v
    }

    fun save() {
        val s = _uiState.value
        if (s.name.isBlank()) { _uiState.update { it.copy(errorMessage = "请输入商品名称") }; return }
        if (s.categoryId.isBlank()) { _uiState.update { it.copy(errorMessage = "请输入分类 ID") }; return }
        val price = s.price.toDoubleOrNull()
        if (price == null || price <= 0) { _uiState.update { it.copy(errorMessage = "请输入正确的价格") }; return }
        val stock = s.stock.toIntOrNull()
        if (stock == null || stock < 0) { _uiState.update { it.copy(errorMessage = "请输入正确的库存") }; return }

        val request = ProductRequest(
            categoryId = s.categoryId.toLong(),
            name = s.name.trim(),
            description = s.description.ifBlank { null },
            price = price,
            originalPrice = s.originalPrice.toDoubleOrNull(),
            stock = stock,
            mainImage = s.mainImage.ifBlank { null }
        )

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            val result = if (s.isEdit) {
                repository.updateProduct(productId, request)
            } else {
                repository.addProduct(request)
            }
            result.fold(
                onSuccess = { _uiState.update { it.copy(isSaving = false, saveSuccess = true) } },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isSaving = false, errorMessage = e.message ?: "保存失败")
                    }
                }
            )
        }
    }
}
