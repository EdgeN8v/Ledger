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
    val tripId: Int? = null,
    val businessOnly: Boolean = false,
    val sub: String? = null          // one sub-category of the category; "__none__" = not sorted into one yet
)

data class DetailUiState(
    val request: DetailRequest? = null,
    val sort: String = "time_desc",     // time_desc / time_asc / amount_desc / amount_asc
    val items: List<TransactionDto> = emptyList(),
    val categories: List<CategoryDto> = emptyList(),
    val subs: List<com.ledger.app.data.SubTotalDto> = emptyList(),
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
        val app = getApplication<Application>()
        val cached = com.ledger.app.data.DiskCache.get<List<TransactionDto>>(app, cacheKey(req, "time_desc"))
        _state.value = DetailUiState(
            request = req, loading = cached == null, items = cached ?: emptyList(), endReached = cached != null && cached.size < pageSize,
            categories = com.ledger.app.data.DiskCache.get<List<CategoryDto>>(app, "categories") ?: emptyList()
        )
        viewModelScope.launch {
            try {
                val cats = api().listCategories()
                com.ledger.app.data.DiskCache.put(app, "categories", cats)
                _state.value = _state.value.copy(categories = cats)
            } catch (_: Exception) {
            }
        }
        reload()
        loadSubs()
    }

    /** How the category splits into its sub-categories (net of refunds), for the chips on top. */
    private fun loadSubs() {
        val r = _state.value.request ?: return
        val cid = r.categoryId ?: return
        viewModelScope.launch {
            try {
                val got = api().subTotals(cid, r.dateFrom, r.dateTo, !com.ledger.app.data.Prefs.getIncludeBusiness(getApplication()))
                _state.value = _state.value.copy(subs = got)
            } catch (_: Exception) {
            }
        }
    }

    fun setSubFilter(sub: String?) {
        val r = _state.value.request ?: return
        if (r.sub == sub) return
        val next = r.copy(sub = sub)
        val cached = com.ledger.app.data.DiskCache.get<List<TransactionDto>>(getApplication<Application>(), cacheKey(next, _state.value.sort))
        _state.value = _state.value.copy(request = next, items = cached ?: emptyList(), endReached = false, loading = cached == null)
        reload()
    }

    /** Pick the kind inside the category (clothes, snacks...); the server remembers it for that shop. */
    fun setSub(id: Int, sub: String?) {
        viewModelScope.launch {
            try {
                api().updateTransaction(id, mapOf("sub" to (sub ?: "")))
                reload()
                loadSubs()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "修改失败")
            }
        }
    }

    fun close() { _state.value = DetailUiState() }

    fun reloadCategories() {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(categories = api().listCategories())
            } catch (_: Exception) {
            }
        }
    }

    private fun cacheKey(r: DetailRequest, sort: String) =
        "detail_" + (listOf(r.categoryId, r.sub, r.query, r.dateFrom, r.dateTo, r.expenseOnly, r.tripId, r.businessOnly, sort,
            com.ledger.app.data.Prefs.getIncludeBusiness(getApplication())).joinToString("|")).hashCode()

    fun setSort(sort: String) {
        if (sort == _state.value.sort) return
        val r = _state.value.request
        val cached = if (r != null) com.ledger.app.data.DiskCache.get<List<TransactionDto>>(getApplication<Application>(), cacheKey(r, sort)) else null
        _state.value = _state.value.copy(sort = sort, items = cached ?: emptyList(), endReached = false, loading = cached == null)
        reload()
    }

    private suspend fun fetch(offset: Int, limit: Int): List<TransactionDto> {
        val s = _state.value
        val r = s.request ?: return emptyList()
        return api().listTransactions(
            dateFrom = if (r.tripId != null) null else r.dateFrom, dateTo = if (r.tripId != null) null else r.dateTo, categoryId = r.categoryId, sub = r.sub,
            txType = if (r.expenseOnly) "expense" else null, kind = "normal", q = r.query, tripId = r.tripId, businessOnly = r.businessOnly,
            excludeBusiness = r.tripId == null && !com.ledger.app.data.Prefs.getIncludeBusiness(getApplication()),
            sort = s.sort, limit = limit, offset = offset
        )
    }

    private fun reload() {
        viewModelScope.launch {
            try {
                val page = fetch(0, maxOf(pageSize, _state.value.items.size).coerceAtMost(500))
                _state.value = _state.value.copy(loading = false, items = page, endReached = page.size < pageSize)
                _state.value.request?.let { com.ledger.app.data.DiskCache.put(getApplication<Application>(), cacheKey(it, _state.value.sort), page) }
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
                loadSubs()
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
            if (_state.value.report == null) com.ledger.app.data.DiskCache.get<ReportDto>(getApplication<Application>(), "report")?.let { _state.value = ReportUiState(report = it) }
            _state.value = _state.value.copy(loading = _state.value.report == null, error = null)
            try {
                val r = ApiClient.create(getApplication()).latestReport()
                com.ledger.app.data.DiskCache.put(getApplication<Application>(), "report", r)
                _state.value = ReportUiState(report = r)
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
            if (_state.value.trips.isEmpty()) com.ledger.app.data.DiskCache.get<List<com.ledger.app.data.TripDto>>(getApplication<Application>(), "trips")?.let { _state.value = TripsUiState(trips = it) }
            _state.value = _state.value.copy(loading = _state.value.trips.isEmpty(), error = null)
            try {
                val tr = api().listTrips()
                val sg = api().tripSuggestions()
                com.ledger.app.data.DiskCache.put(getApplication<Application>(), "trips", tr)
                _state.value = TripsUiState(trips = tr, suggestions = sg)
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

    fun update(t: com.ledger.app.data.TripDto, name: String, start: String, end: String, daily: String?, commute: String?) {
        viewModelScope.launch {
            try {
                api().updateTrip(t.id, com.ledger.app.data.TripInRequest(name, start, end, t.kind, daily, commute))
                load()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "修改失败")
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
