package com.ecommerce.buyer.feature.home

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

/**
 * 首页 UI 状态
 */
data class HomeUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val products: List<Product> = emptyList(),
    val currentPage: Int = 1,
    val totalPages: Long = 1,
    val errorMessage: String? = null,
    val keyword: String = ""
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val productRepository: ProductRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState(isLoading = true))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadFirstPage()
    }

    /**
     * 加载第一页（进入页面/刷新）
     */
    fun loadFirstPage() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = productRepository.getProductList(current = 1, size = 10, keyword = _uiState.value.keyword.ifBlank { null })
            result.fold(
                onSuccess = { page ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            products = page.records,
                            currentPage = 1,
                            totalPages = page.pages,
                            errorMessage = null
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isLoading = false, isRefreshing = false, errorMessage = e.message ?: "加载失败")
                    }
                }
            )
        }
    }

    /**
     * 下拉刷新
     */
    fun refresh() {
        _uiState.update { it.copy(isRefreshing = true) }
        loadFirstPage()
    }

    /**
     * 上拉加载更多
     */
    fun loadMore() {
        val current = _uiState.value
        if (current.isLoadingMore || current.currentPage >= current.totalPages) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            val nextPage = current.currentPage + 1
            val result = productRepository.getProductList(
                current = nextPage,
                size = 10,
                keyword = current.keyword.ifBlank { null }
            )
            result.fold(
                onSuccess = { page ->
                    _uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            products = it.products + page.records,
                            currentPage = nextPage,
                            totalPages = page.pages
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isLoadingMore = false, errorMessage = e.message ?: "加载更多失败")
                    }
                }
            )
        }
    }

    /**
     * 搜索
     */
    fun search(keyword: String) {
        _uiState.update { it.copy(keyword = keyword) }
        loadFirstPage()
    }
}
