package com.ecommerce.seller.feature.profile

import androidx.lifecycle.ViewModel
import com.ecommerce.core.datastore.TokenManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class ProfileUiState(
    val isLoggedIn: Boolean = false,
    val username: String? = null,
    val userId: Long = 0L
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val tokenManager: TokenManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ProfileUiState(
            isLoggedIn = tokenManager.isLoggedIn,
            username = tokenManager.username,
            userId = tokenManager.userId
        )
    )
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    fun refresh() {
        _uiState.value = ProfileUiState(
            isLoggedIn = tokenManager.isLoggedIn,
            username = tokenManager.username,
            userId = tokenManager.userId
        )
    }

    fun logout() {
        tokenManager.clear()
        refresh()
    }
}
