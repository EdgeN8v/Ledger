package com.ledger.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ledger.app.data.ApiClient
import com.ledger.app.data.CycleAnalysisDto
import com.ledger.app.data.PlanDto
import com.ledger.app.data.PlanInRequest
import com.ledger.app.data.PlanStatusDto
import com.ledger.app.data.YearAnalysisDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class PlanUiState(
    val loading: Boolean = false,
    val plan: PlanDto? = null,
    val status: PlanStatusDto? = null,
    val error: String? = null
)

class PlanViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(PlanUiState())
    val state: StateFlow<PlanUiState> = _state
    private fun api() = ApiClient.create(getApplication())

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                _state.value = PlanUiState(plan = api().getPlan(), status = api().planStatus())
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
    }

    fun save(expectedIncome: String?, savingsTarget: String?, fixedExpenses: String?) {
        viewModelScope.launch {
            try {
                api().setPlan(PlanInRequest(expectedIncome, savingsTarget, fixedExpenses))
                _state.value = PlanUiState(plan = api().getPlan(), status = api().planStatus())
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "保存失败")
            }
        }
    }
}

data class InsightsUiState(
    val loading: Boolean = false,
    val cycle: CycleAnalysisDto? = null,
    val year: YearAnalysisDto? = null,
    val selYear: Int = LocalDate.now().year,
    val selMonth: Int = 0,           // 0 = the current pay cycle, decided by the server
    val error: String? = null
)

class InsightsViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(InsightsUiState())
    val state: StateFlow<InsightsUiState> = _state
    private fun api() = ApiClient.create(getApplication())

    fun loadCycle() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val s = _state.value
                val inc = com.ledger.app.data.Prefs.getIncludeBusiness(getApplication())
                val c = if (s.selMonth == 0) api().analysisCycle(null, null, inc) else api().analysisCycle(s.selYear, s.selMonth, inc)
                _state.value = _state.value.copy(loading = false, cycle = c, selYear = c.year, selMonth = c.month)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
    }

    /** Fix a category straight from 'the biggest purchases'; the server also learns it for that merchant. */
    fun setCategory(txId: Int, categoryId: Int) {
        viewModelScope.launch {
            try {
                api().updateTransaction(txId, mapOf("category_id" to categoryId, "needs_review" to false))
                loadCycle()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "修改失败")
            }
        }
    }

    fun shiftCycle(delta: Int) {
        val s = _state.value
        var y = s.selYear
        var m = (if (s.selMonth == 0) LocalDate.now().monthValue else s.selMonth) + delta
        if (m < 1) { m = 12; y -= 1 }
        if (m > 12) { m = 1; y += 1 }
        _state.value = s.copy(selYear = y, selMonth = m)
        loadCycle()
    }

    fun loadYear(year: Int = _state.value.selYear) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                _state.value = _state.value.copy(loading = false, year = api().analysisYear(year, com.ledger.app.data.Prefs.getIncludeBusiness(getApplication())), selYear = year)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
    }
}
