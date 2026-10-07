package com.ledger.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ledger.app.data.ApiClient
import com.ledger.app.data.CategoryDto
import com.ledger.app.data.ReportDto
import com.ledger.app.data.TransactionDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the drill-down shows: every transaction of one category (or one shop) inside a pay cycle. */
data class DetailRequest(
    val title: String,
    val categoryId: Int?,
    val query: String?,
    val dateFrom: String,
    val dateTo: String,
    val expenseOnly: Boolean = true,
    val tripId: Int? = null
)

data class DetailUiState(
    val request: DetailRequest? = null,
    val sort: String = "time_desc",     // time_desc / time_asc / amount_desc / amount_asc
    val items: List<TransactionDto> = emptyList(),
    val categories: List<CategoryDto> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null
)

class DetailViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state
    private val pageSize = 50
    private fun api() = ApiClient.create(getApplication())

    fun open(req: DetailRequest) {
        _state.value = DetailUiState(request = req, loading = true)
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(categories = api().listCategories())
            } catch (_: Exception) {
            }
        }
        reload()
    }

    fun close() { _state.value = DetailUiState() }

    fun setSort(sort: String) {
        if (sort == _state.value.sort) return
        _state.value = _state.value.copy(sort = sort, items = emptyList(), endReached = false, loading = true)
        reload()
    }

    private suspend fun fetch(offset: Int, limit: Int): List<TransactionDto> {
        val s = _state.value
        val r = s.request ?: return emptyList()
        return api().listTransactions(
            dateFrom = r.dateFrom, dateTo = r.dateTo, categoryId = r.categoryId,
            txType = if (r.expenseOnly) "expense" else null, kind = "normal", q = r.query, tripId = r.tripId,
            excludeBusiness = r.tripId == null && !com.ledger.app.data.Prefs.getIncludeBusiness(getApplication()),
            sort = s.sort, limit = limit, offset = offset
        )
    }

    private fun reload() {
        viewModelScope.launch {
            try {
                val page = fetch(0, maxOf(pageSize, _state.value.items.size).coerceAtMost(500))
                _state.value = _state.value.copy(loading = false, items = page, endReached = page.size < pageSize)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || s.endReached || s.request == null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingMore = true)
            try {
                val page = fetch(s.items.size, pageSize)
                _state.value = _state.value.copy(loadingMore = false, items = _state.value.items + page, endReached = page.size < pageSize)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loadingMore = false, error = e.message ?: "加载失败")
            }
        }
    }

    /** Long-press fix, same as on the main list. */
    fun setCategory(id: Int, categoryId: Int) {
        viewModelScope.launch {
            try {
                api().updateTransaction(id, mapOf("category_id" to categoryId, "needs_review" to false))
                reload()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "修改失败")
            }
        }
    }
}

data class ReportUiState(val loading: Boolean = false, val report: ReportDto? = null, val error: String? = null)

class ReportViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ReportUiState())
    val state: StateFlow<ReportUiState> = _state
    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                _state.value = ReportUiState(report = ApiClient.create(getApplication()).latestReport())
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
    }
}

data class TripsUiState(
    val loading: Boolean = false,
    val trips: List<com.ledger.app.data.TripDto> = emptyList(),
    val suggestions: List<com.ledger.app.data.TripSuggestionDto> = emptyList(),
    val error: String? = null
)

class TripsViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(TripsUiState())
    val state: StateFlow<TripsUiState> = _state
    private fun api() = ApiClient.create(getApplication())

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                _state.value = TripsUiState(trips = api().listTrips(), suggestions = api().tripSuggestions())
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
    }

    fun create(name: String, start: String, end: String, kind: String = "personal", daily: String? = null, commute: String? = null) {
        viewModelScope.launch {
            try {
                api().createTrip(com.ledger.app.data.TripInRequest(name, start, end, kind, daily, commute))
                load()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "创建失败")
            }
        }
    }

    /** Recent incoming money, for picking "this deposit was the employer paying the trip back". */
    suspend fun recentIncomes(): List<com.ledger.app.data.TransactionDto> = try {
        api().listTransactions(txType = "income", limit = 30)
    } catch (e: Exception) {
        emptyList()
    }

    fun settle(tripId: Int, txId: Int) {
        viewModelScope.launch {
            try {
                api().settleTrip(tripId, com.ledger.app.data.SettleRequest(txId))
                load()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "操作失败")
            }
        }
    }

    fun unsettle(tripId: Int) {
        viewModelScope.launch {
            try {
                api().unsettleTrip(tripId)
                load()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "操作失败")
            }
        }
    }

    fun dismiss(start: String, end: String) {
        viewModelScope.launch {
            try {
                api().dismissTripSuggestion(com.ledger.app.data.TripRefRequest(start, end))
                load()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "操作失败")
            }
        }
    }

    fun delete(id: Int) {
        viewModelScope.launch {
            try {
                api().deleteTrip(id)
                load()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "删除失败")
            }
        }
    }
}
