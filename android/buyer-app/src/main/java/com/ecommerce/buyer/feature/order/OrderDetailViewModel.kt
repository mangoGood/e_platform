package com.ecommerce.buyer.feature.order

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ecommerce.core.model.Order
import com.ecommerce.core.model.OrderVO
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OrderDetailUiState(
    val isLoading: Boolean = true,
    val order: OrderVO? = null,
    val errorMessage: String? = null,
    val actionSuccess: String? = null,
    val isProcessing: Boolean = false
)

@HiltViewModel
class OrderDetailViewModel @Inject constructor(
    private val repository: OrderRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val orderId: Long = savedStateHandle.get<String>("orderId")?.toLongOrNull() ?: 0L

    private val _uiState = MutableStateFlow(OrderDetailUiState())
    val uiState: StateFlow<OrderDetailUiState> = _uiState.asStateFlow()

    init {
        loadOrder()
    }

    fun loadOrder() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            repository.getOrderDetail(orderId)
                .onSuccess { order ->
                    _uiState.update { it.copy(isLoading = false, order = order) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = e.message ?: "加载失败")
                    }
                }
        }
    }

    fun payOrder() {
        performAction(
            successMessage = "支付成功",
            nextStatus = Order.STATUS_PAID
        ) { repository.payOrder(orderId) }
    }

    fun cancelOrder() {
        performAction(
            successMessage = "订单已取消",
            nextStatus = Order.STATUS_CANCELLED
        ) { repository.cancelOrder(orderId) }
    }

    fun receiveOrder() {
        performAction(
            successMessage = "确认收货成功",
            nextStatus = Order.STATUS_COMPLETED
        ) { repository.receiveOrder(orderId) }
    }

    /**
     * 执行一次会改变订单状态的操作，成功后把本地状态推进到 [nextStatus]。
     *
     * ## 修复的存量 Bug
     * 改造前这里是一个所有操作共用的 `when` 表达式：
     * ```kotlin
     * status = when (state.order.status) {
     *     Order.STATUS_UNPAID -> Order.STATUS_PAID
     *     else -> state.order.status   // ← 其余状态原样返回
     * }
     * ```
     * 它只认识"待付款 -> 待发货"这一条边。于是：
     * - **确认收货**：订单处于 `STATUS_SHIPPED(2)`，命中 `else` 分支，status 原样保留 2，
     *   `copy()` 出来的对象和旧对象**数据完全相等**。StateFlow 用 `equals` 去重，
     *   相等就不发射新值，Compose 收不到重组信号——**接口明明调成功了，UI 纹丝不动**，
     *   按钮还停在"确认收货"上，用户只能重进页面才看到变化。
     * - **取消订单**：同样命中 `else`，状态不会变成"已取消"。
     *
     * 根因是"状态如何推进"这件事被错误地从**动作**身上剥离、改由**当前状态**去猜。
     * 现在由每个调用方显式声明自己的目标状态，三条转移边全部覆盖。
     *
     * @param successMessage 成功后展示给用户的中文提示
     * @param nextStatus     操作成功后订单应处于的状态
     * @param action         实际的仓库调用
     */
    private fun performAction(
        successMessage: String,
        nextStatus: Int,
        action: suspend () -> Result<Unit>
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, errorMessage = null) }
            action()
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            isProcessing = false,
                            order = state.order?.copy(status = nextStatus),
                            actionSuccess = successMessage
                        )
                    }
                    // 本地推进只是让 UI 立刻响应；随后拉一次真实数据，
                    // 把服务端才有的 payTime / deliveryTime / receiveTime 补齐。
                    refreshQuietly()
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isProcessing = false, errorMessage = e.message ?: "操作失败")
                    }
                }
        }
    }

    /**
     * 静默刷新：不翻转 `isLoading`，避免整页闪一下骨架屏。
     * 失败时也不打扰用户——本地状态已经推进过了，界面是正确的。
     */
    private fun refreshQuietly() {
        viewModelScope.launch {
            repository.getOrderDetail(orderId)
                .onSuccess { order ->
                    _uiState.update { it.copy(order = order) }
                }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, actionSuccess = null) }
    }
}
