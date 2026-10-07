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
    val reminders: List<com.ledger.app.data.BillReminderDto> = emptyList(),
    val error: String? = null
)

class PlanViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(PlanUiState())
    val state: StateFlow<PlanUiState> = _state
    private fun api() = ApiClient.create(getApplication())

    fun load() {
        viewModelScope.launch {
            if (_state.value.plan == null) {
                val p = com.ledger.app.data.DiskCache.get<PlanDto>(getApplication<Application>(), "plan")
                val st = com.ledger.app.data.DiskCache.get<PlanStatusDto>(getApplication<Application>(), "plan_status")
                if (p != null) _state.value = PlanUiState(plan = p, status = st, reminders = com.ledger.app.data.DiskCache.get<List<com.ledger.app.data.BillReminderDto>>(getApplication<Application>(), "reminders") ?: emptyList())
            }
            _state.value = _state.value.copy(loading = _state.value.plan == null, error = null)
            try {
                val p = api().getPlan()
                val st = api().planStatus()
                com.ledger.app.data.DiskCache.put(getApplication<Application>(), "plan", p)
                com.ledger.app.data.DiskCache.put(getApplication<Application>(), "plan_status", st)
                val rm = try { api().billReminders() } catch (_: Exception) { _state.value.reminders }
                com.ledger.app.data.DiskCache.put(getApplication<Application>(), "reminders", rm)
                _state.value = PlanUiState(plan = p, status = st, reminders = rm)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
    }

    fun save(expectedIncome: String?, savingsTarget: String?, fixedExpenses: String?) {
        viewModelScope.launch {
            try {
                api().setPlan(PlanInRequest(expectedIncome, savingsTarget, fixedExpenses))
                _state.value = _state.value.copy(plan = api().getPlan(), status = api().planStatus())
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
            val s0 = _state.value
            val inc = com.ledger.app.data.Prefs.getIncludeBusiness(getApplication())
            val ckey = "cycle_" + s0.selYear + "_" + s0.selMonth + "_" + inc
            val cc = if (s0.cycle == null) com.ledger.app.data.DiskCache.get<CycleAnalysisDto>(getApplication<Application>(), ckey) else null
            _state.value = s0.copy(loading = s0.cycle == null && cc == null, error = null, cycle = s0.cycle ?: cc)
            try {
                val s = _state.value
                val c = if (s.selMonth == 0) api().analysisCycle(null, null, inc) else api().analysisCycle(s.selYear, s.selMonth, inc)
                com.ledger.app.data.DiskCache.put(getApplication<Application>(), ckey, c)
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
            val inc = com.ledger.app.data.Prefs.getIncludeBusiness(getApplication())
            val ykey = "year_" + year + "_" + inc
            val cy = com.ledger.app.data.DiskCache.get<YearAnalysisDto>(getApplication<Application>(), ykey)
            _state.value = _state.value.copy(loading = _state.value.year == null && cy == null, error = null, year = cy ?: _state.value.year, selYear = year)
            try {
                val ya = api().analysisYear(year, inc)
                com.ledger.app.data.DiskCache.put(getApplication<Application>(), ykey, ya)
                _state.value = _state.value.copy(loading = false, year = ya, selYear = year)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
    }
}
