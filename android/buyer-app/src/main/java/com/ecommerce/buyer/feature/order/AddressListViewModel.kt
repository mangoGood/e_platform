package com.ecommerce.buyer.feature.order

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ecommerce.core.model.Address
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddressListUiState(
    val isLoading: Boolean = true,
    val addresses: List<Address> = emptyList(),
    val selectedAddress: Address? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class AddressListViewModel @Inject constructor(
    private val repository: CheckoutRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddressListUiState())
    val uiState: StateFlow<AddressListUiState> = _uiState.asStateFlow()

    init {
        loadAddresses()
    }

    private fun loadAddresses() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            repository.loadAddresses()
                .onSuccess { addresses ->
                    val default = addresses.find { it.isDefault == 1 } ?: addresses.firstOrNull()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            addresses = addresses,
                            selectedAddress = default
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

    fun selectAddress(address: Address) {
        _uiState.update { it.copy(selectedAddress = address) }
    }
}
