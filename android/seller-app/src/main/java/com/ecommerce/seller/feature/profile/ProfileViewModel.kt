package com.ecommerce.seller.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ecommerce.core.datastore.TokenManager
import com.ecommerce.seller.feature.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val isLoggedIn: Boolean = false,
    val username: String? = null,
    val userId: Long = 0L,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val tokenManager: TokenManager,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(snapshot())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    /**
     * 从本地存储读一份当前登录态快照。
     */
    private fun snapshot(): ProfileUiState = ProfileUiState(
        isLoggedIn = tokenManager.isLoggedIn,
        username = tokenManager.username,
        userId = tokenManager.userId
    )

    fun refresh() {
        _uiState.value = snapshot()
    }

    /**
     * 退出登录。
     *
     * ## 改造要点
     * 原实现是一行 `tokenManager.clear()`，纯本地清理——服务端那枚 token 依旧有效。
     * 现在走 [AuthRepository.logout]：先 `POST user/logout` 让后端把 jti 写进黑名单，
     * 再清本地。因为要发网络请求，方法体挪进了 `viewModelScope`。
     */
    fun logout() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            authRepository.logout()
                .onSuccess {
                    _uiState.value = snapshot().copy(isLoading = false)
                }
                .onFailure { e ->
                    // logout 内部已保证本地一定被清空，这里只是把异常文案透出来
                    _uiState.value = snapshot().copy(
                        isLoading = false,
                        errorMessage = e.message ?: "退出失败"
                    )
                }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
