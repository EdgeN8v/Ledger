package com.ledger.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ledger.app.data.ApiClient
import com.ledger.app.data.MonthSummaryDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

data class StatisticsUiState(
    val year: Int,
    val month: Int,
    val loading: Boolean = false,
    val summary: MonthSummaryDto? = null,
    val initialised: Boolean = false,
    val error: String? = null
)

class StatisticsViewModel(application: Application) : AndroidViewModel(application) {

    private val now = Calendar.getInstance()
    private val _state = MutableStateFlow(
        StatisticsUiState(year = now.get(Calendar.YEAR), month = now.get(Calendar.MONTH) + 1)
    )
    val state: StateFlow<StatisticsUiState> = _state

    private fun api() = ApiClient.create(getApplication())

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                if (!_state.value.initialised) {
                    // budget months start on payday: before that day we are still in last month's cycle
                    val startDay = api().me().cycle_start_day
                    var y = now.get(Calendar.YEAR)
                    var m = now.get(Calendar.MONTH) + 1
                    if (now.get(Calendar.DAY_OF_MONTH) < startDay) { m -= 1; if (m < 1) { m = 12; y -= 1 } }
                    _state.value = _state.value.copy(year = y, month = m, initialised = true)
                }
                val summary = api().monthlySummary(_state.value.year, _state.value.month)
                _state.value = _state.value.copy(loading = false, summary = summary)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
    }

    fun previousMonth() {
        var year = _state.value.year
        var month = _state.value.month - 1
        if (month < 1) { month = 12; year -= 1 }
        _state.value = _state.value.copy(year = year, month = month)
        load()
    }

    fun nextMonth() {
        var year = _state.value.year
        var month = _state.value.month + 1
        if (month > 12) { month = 1; year += 1 }
        _state.value = _state.value.copy(year = year, month = month)
        load()
    }

    fun setYearMonth(year: Int, month: Int) {
        _state.value = _state.value.copy(year = year, month = month)
        load()
    }
}
