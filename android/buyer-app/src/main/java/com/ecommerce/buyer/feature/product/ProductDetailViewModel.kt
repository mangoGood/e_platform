package com.ecommerce.buyer.feature.product

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ecommerce.core.model.CommentVO
import com.ecommerce.core.model.Product
import com.ecommerce.core.model.RatingSummaryVO
import com.ecommerce.core.model.ViewerContextVO
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 评论区 UI 状态。
 *
 * 单独抽一个 data class 而不是把十几个字段平铺进 [ProductDetailUiState]：
 * 评论区是一块可以独立加载、独立失败、独立重试的区域，
 * 商品主体加载失败时评论区不该跟着消失，反之亦然。
 */
data class CommentUiState(
    val isLoading: Boolean = false,
    /** L1 评价列表。来自 `data.comments.records`，注意不是 `data.records` */
    val comments: List<CommentVO> = emptyList(),
    val summary: RatingSummaryVO = RatingSummaryVO(),
    /** 权限上下文。UI 的"可评价/可追问"一律以它为准，不在客户端自己推断 */
    val viewerContext: ViewerContextVO = ViewerContextVO(),
    val total: Long = 0L,
    val currentPage: Int = 1,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
    /** 正在提交评价/追问，用于禁用按钮防重复提交 */
    val isSubmitting: Boolean = false,
    /** 当前展开了追问输入框的 L1 评价 id；null 表示没有展开 */
    val askingCommentId: Long? = null,
    /** 是否展开发表评价的表单 */
    val isReviewFormOpen: Boolean = false,
    /** 已在本地展开"全部追问"的 L1 评价 id 集合 */
    val expandedAsks: Set<Long> = emptySet()
)

data class ProductDetailUiState(
    val isLoading: Boolean = true,
    val product: Product? = null,
    val quantity: Int = 1,
    val errorMessage: String? = null,
    val addToCartSuccess: Boolean = false,
    val comment: CommentUiState = CommentUiState()
)

@HiltViewModel
class ProductDetailViewModel @Inject constructor(
    private val repository: ProductDetailRepository,
    private val commentRepository: CommentRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProductDetailUiState())
    val uiState: StateFlow<ProductDetailUiState> = _uiState.asStateFlow()

    /** 当前页面对应的商品 id，写操作成功后用它重新拉评论 */
    private var currentProductId: Long = 0L

    /**
     * 加载商品详情，并并行触发评论区加载。
     *
     * @param productId 商品 id
     */
    fun loadProduct(productId: Long) {
        currentProductId = productId
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
        loadComments(productId)
    }

    // -----------------------------------------------------------------------
    // 评论区
    // -----------------------------------------------------------------------

    /**
     * 加载第一页评论。
     *
     * @param productId 商品 id
     */
    fun loadComments(productId: Long = currentProductId) {
        if (productId <= 0L) return
        viewModelScope.launch {
            updateComment { it.copy(isLoading = true, errorMessage = null) }
            commentRepository.getProductComments(productId = productId, pageNum = FIRST_PAGE)
                .onSuccess { tree ->
                    val page = tree.comments
                    updateComment {
                        it.copy(
                            isLoading = false,
                            // 取值层级：评论树里列表在 comments.records，比回复接口多一层
                            comments = page.records,
                            summary = tree.summary,
                            viewerContext = tree.viewerContext,
                            total = page.total,
                            currentPage = FIRST_PAGE,
                            hasMore = page.current < page.pages,
                            errorMessage = null,
                            expandedAsks = emptySet()
                        )
                    }
                }
                .onFailure { e ->
                    updateComment {
                        it.copy(isLoading = false, errorMessage = e.message ?: "评论加载失败")
                    }
                }
        }
    }

    /**
     * 加载下一页评论，追加到列表尾部。
     */
    fun loadMoreComments() {
        val state = _uiState.value.comment
        if (state.isLoadingMore || !state.hasMore || currentProductId <= 0L) return
        val nextPage = state.currentPage + 1
        viewModelScope.launch {
            updateComment { it.copy(isLoadingMore = true) }
            commentRepository.getProductComments(
                productId = currentProductId,
                pageNum = nextPage
            )
                .onSuccess { tree ->
                    val page = tree.comments
                    updateComment {
                        it.copy(
                            isLoadingMore = false,
                            comments = it.comments + page.records,
                            currentPage = nextPage,
                            hasMore = page.current < page.pages,
                            total = page.total
                        )
                    }
                }
                .onFailure { e ->
                    updateComment {
                        it.copy(isLoadingMore = false, errorMessage = e.message ?: "加载更多失败")
                    }
                }
        }
    }

    /**
     * 展开某条评价的全部追问。
     *
     * 评论树里内联的 `asks` 只有 `askSize` 条，展开时才去打分页接口拿全量。
     * 注意这个接口的列表在 **`data.records`**（比评论树少一层）。
     *
     * @param rootId L1 评价 id
     */
    fun expandAsks(rootId: Long) {
        if (_uiState.value.comment.expandedAsks.contains(rootId)) return
        viewModelScope.launch {
            commentRepository.getReplies(rootId = rootId, pageSize = ALL_ASKS_PAGE_SIZE)
                .onSuccess { page ->
                    updateComment { state ->
                        state.copy(
                            comments = state.comments.map { comment ->
                                if (comment.id == rootId) {
                                    comment.copy(
                                        asks = page.records,
                                        askTotal = page.total.toInt(),
                                        askHasMore = false
                                    )
                                } else {
                                    comment
                                }
                            },
                            expandedAsks = state.expandedAsks + rootId
                        )
                    }
                }
                .onFailure { e ->
                    updateComment { it.copy(errorMessage = e.message ?: "追问加载失败") }
                }
        }
    }

    /**
     * 发表 L1 评价。
     *
     * @param rating  评分 1..5
     * @param content 正文
     */
    fun submitReview(rating: Int, content: String) {
        if (content.isBlank()) {
            updateComment { it.copy(errorMessage = "评价内容不能为空") }
            return
        }
        if (_uiState.value.comment.isSubmitting) return
        viewModelScope.launch {
            updateComment { it.copy(isSubmitting = true, errorMessage = null) }
            commentRepository.createComment(
                productId = currentProductId,
                rating = rating,
                content = content
            )
                .onSuccess {
                    updateComment { it.copy(isSubmitting = false, isReviewFormOpen = false) }
                    // 重新拉全量：新评价的排序位置、viewerContext.canReview 都由后端决定，
                    // 本地插一条会和服务端状态不一致
                    loadComments()
                }
                .onFailure { e ->
                    updateComment {
                        it.copy(isSubmitting = false, errorMessage = e.message ?: "评价发表失败")
                    }
                }
        }
    }

    /**
     * 发表 L3 追问。
     *
     * @param commentId 被追问的 L1 评价 id
     * @param content   正文
     */
    fun submitAsk(commentId: Long, content: String) {
        if (content.isBlank()) {
            updateComment { it.copy(errorMessage = "追问内容不能为空") }
            return
        }
        if (_uiState.value.comment.isSubmitting) return
        viewModelScope.launch {
            updateComment { it.copy(isSubmitting = true, errorMessage = null) }
            commentRepository.askComment(commentId = commentId, content = content)
                .onSuccess {
                    updateComment { it.copy(isSubmitting = false, askingCommentId = null) }
                    loadComments()
                }
                .onFailure { e ->
                    updateComment {
                        it.copy(isSubmitting = false, errorMessage = e.message ?: "追问发表失败")
                    }
                }
        }
    }

    /**
     * 删除自己的评论。
     *
     * @param commentId 评论 id
     */
    fun deleteComment(commentId: Long) {
        viewModelScope.launch {
            updateComment { it.copy(isSubmitting = true, errorMessage = null) }
            commentRepository.deleteComment(commentId)
                .onSuccess {
                    updateComment { it.copy(isSubmitting = false) }
                    loadComments()
                }
                .onFailure { e ->
                    updateComment {
                        it.copy(isSubmitting = false, errorMessage = e.message ?: "删除失败")
                    }
                }
        }
    }

    /** 展开/收起某条评价的追问输入框；传 null 收起 */
    fun toggleAskInput(commentId: Long?) {
        updateComment {
            it.copy(askingCommentId = if (it.askingCommentId == commentId) null else commentId)
        }
    }

    /** 展开/收起发表评价的表单 */
    fun toggleReviewForm(open: Boolean) {
        updateComment { it.copy(isReviewFormOpen = open) }
    }

    fun clearCommentError() {
        updateComment { it.copy(errorMessage = null) }
    }

    // -----------------------------------------------------------------------
    // 商品主体
    // -----------------------------------------------------------------------

    fun changeQuantity(delta: Int) {
        _uiState.update {
            it.copy(quantity = (it.quantity + delta).coerceAtLeast(1))
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

    /**
     * 只更新嵌套的评论区状态，少写一层 `copy(comment = it.comment.copy(...))`。
     *
     * @param transform 对 [CommentUiState] 的变换
     */
    private inline fun updateComment(crossinline transform: (CommentUiState) -> CommentUiState) {
        _uiState.update { it.copy(comment = transform(it.comment)) }
    }

    private companion object {
        const val FIRST_PAGE = 1
        /** 展开全部追问时用的页大小：树深恒为 2，单条评价下的追问量级有限，一次拉完最省事 */
        const val ALL_ASKS_PAGE_SIZE = 50
    }
}
