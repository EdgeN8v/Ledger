package com.ledger.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ledger.app.data.AccountCreateRequest
import com.ledger.app.data.AccountsOverviewDto
import com.ledger.app.data.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AccountsUiState(
    val loading: Boolean = false,
    val overview: AccountsOverviewDto? = null,
    val error: String? = null
)

class AccountsViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(AccountsUiState())
    val state: StateFlow<AccountsUiState> = _state

    private fun api() = ApiClient.create(getApplication())

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                _state.value = AccountsUiState(overview = api().listAccounts())
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
    }

    fun add(name: String, kind: String, balance: String?) {
        viewModelScope.launch {
            try {
                _state.value = AccountsUiState(overview = api().createAccount(AccountCreateRequest(name, kind, balance)))
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "添加失败")
            }
        }
    }

    fun update(id: Int, balance: String?, includeInTotal: Boolean?) {
        viewModelScope.launch {
            try {
                val body = mutableMapOf<String, Any?>()
                if (balance != null) body["balance"] = balance
                if (includeInTotal != null) body["include_in_total"] = includeInTotal
                _state.value = AccountsUiState(overview = api().updateAccount(id, body))
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "更新失败")
            }
        }
    }
}
