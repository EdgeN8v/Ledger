package com.ledger.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ledger.app.data.ApiClient
import com.ledger.app.data.JoinRequest
import com.ledger.app.data.Prefs
import com.ledger.app.data.RegisterRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val loggedIn: Boolean = false
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(AuthUiState(loggedIn = Prefs.isLoggedIn(application)))
    val state: StateFlow<AuthUiState> = _state

    fun getBaseUrl(): String = Prefs.getBaseUrl(getApplication())
    fun setBaseUrl(url: String) = Prefs.setBaseUrl(getApplication(), url)

    fun login(username: String, password: String) {
        runAuthCall { ApiClient.create(getApplication()).login(username, password) }
    }

    fun register(username: String, password: String, displayName: String, ledgerName: String?) {
        runAuthCall {
            ApiClient.create(getApplication())
                .register(RegisterRequest(username, password, displayName, ledgerName))
        }
    }

    fun join(username: String, password: String, displayName: String, inviteCode: String) {
        runAuthCall {
            ApiClient.create(getApplication())
                .join(JoinRequest(username, password, displayName, inviteCode))
        }
    }

    fun logout() {
        Prefs.logout(getApplication())
        _state.value = AuthUiState(loggedIn = false)
    }

    private fun runAuthCall(block: suspend () -> com.ledger.app.data.TokenResponse) {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val token = block()
                Prefs.setToken(getApplication(), token.access_token)
                _state.value = AuthUiState(loggedIn = true)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "网络请求失败")
            }
        }
    }
}
