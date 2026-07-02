package com.ecommerce.buyer.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ecommerce.buyer.feature.auth.AuthRepository
import com.ecommerce.core.datastore.TokenManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 个人中心 UI 状态
 */
data class ProfileUiState(
    val isLoggedIn: Boolean = false,
    val username: String? = null,
    val userId: Long = 0L,
    val userType: Int = 0,
    val isLoading: Boolean = false,
    val logoutSuccess: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(loadInitialState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private fun loadInitialState(): ProfileUiState {
        return ProfileUiState(
            isLoggedIn = tokenManager.isLoggedIn,
            username = tokenManager.username,
            userId = tokenManager.userId,
            userType = tokenManager.userType
        )
    }

    /**
     * 重新加载用户信息（例如登录后回到个人中心）
     */
    fun refresh() {
        _uiState.update {
            it.copy(
                isLoggedIn = tokenManager.isLoggedIn,
                username = tokenManager.username,
                userId = tokenManager.userId,
                userType = tokenManager.userType
            )
        }
    }

    /**
     * 退出登录
     */
    fun logout() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                authRepository.logout()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoggedIn = false,
                        username = null,
                        userId = 0L,
                        userType = 0,
                        logoutSuccess = true
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = e.message ?: "退出失败")
                }
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, logoutSuccess = false) }
    }
}
