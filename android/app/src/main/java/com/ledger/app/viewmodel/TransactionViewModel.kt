package com.ledger.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ledger.app.data.ApiClient
import com.ledger.app.data.CategoryDto
import com.ledger.app.data.MonthSummaryDto
import com.ledger.app.data.TransactionCreateRequest
import com.ledger.app.data.TransactionDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.time.LocalDate

/** What the list is narrowed to. range: all / cycle (current payday month) / prev_cycle / month / last30. */
data class TxFilter(
    val range: String = "all",
    val type: String? = null,          // expense / income
    val categoryId: Int? = null,
    val kind: String? = null,          // transfer / normal
    val query: String = "",
    val needsReviewOnly: Boolean = false,
    val from: String? = null,          // with range "custom": one day (from == to), or a whole month
    val to: String? = null
) {
    val isActive get() = range != "all" || type != null || categoryId != null || kind != null || query.isNotBlank() || needsReviewOnly
}

data class TransactionUiState(
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val categories: List<CategoryDto> = emptyList(),
    val transactions: List<TransactionDto> = emptyList(),
    val inviteCode: String? = null,
    val cycleStartDay: Int = 1,
    val cycleSummary: MonthSummaryDto? = null,
    val businessTrips: List<com.ledger.app.data.TripDto> = emptyList(),
    val filter: TxFilter = TxFilter(),
    val syncing: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

class TransactionViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(TransactionUiState())
    val state: StateFlow<TransactionUiState> = _state

    private val pageSize = 50

    init {
        // show the last known list at once; the silent refresh replaces it
        val app = getApplication<Application>()
        val page = com.ledger.app.data.DiskCache.get<List<TransactionDto>>(app, "tx_page")
        if (page != null) {
            _state.value = _state.value.copy(
                transactions = page,
                endReached = page.size < pageSize,
                categories = com.ledger.app.data.DiskCache.get<List<CategoryDto>>(app, "categories") ?: emptyList(),
                inviteCode = com.ledger.app.data.DiskCache.get<String>(app, "invite"),
                cycleStartDay = com.ledger.app.data.DiskCache.get<Int>(app, "cycle_start") ?: 1,
                cycleSummary = com.ledger.app.data.DiskCache.get<MonthSummaryDto>(app, "cycle_summary_" + com.ledger.app.data.Prefs.getIncludeBusiness(app)),
                businessTrips = com.ledger.app.data.DiskCache.get<List<com.ledger.app.data.TripDto>>(app, "business_trips") ?: emptyList()
            )
        }
    }

    private fun api() = ApiClient.create(getApplication())

    /** Date window for the chosen range, as (from, to) in yyyy-MM-dd; null = unbounded. */
    private fun dateWindow(f: TxFilter, startDay: Int): Pair<String?, String?> {
        val today = LocalDate.now()
        fun cycleStart(d: LocalDate): LocalDate {
            val thisMonth = d.withDayOfMonth(minOf(startDay, d.lengthOfMonth()))
            return if (d.dayOfMonth >= startDay) thisMonth else thisMonth.minusMonths(1)
        }
        return when (f.range) {
            "cycle" -> { val s = cycleStart(today); s.toString() to s.plusMonths(1).minusDays(1).toString() }
            "prev_cycle" -> { val s = cycleStart(today).minusMonths(1); s.toString() to s.plusMonths(1).minusDays(1).toString() }
            "month" -> today.withDayOfMonth(1).toString() to today.withDayOfMonth(today.lengthOfMonth()).toString()
            "last30" -> today.minusDays(29).toString() to today.toString()
            "custom" -> f.from to f.to
            else -> null to null
        }
    }

    private suspend fun fetch(offset: Int, limit: Int): List<TransactionDto> {
        val s = _state.value
        val (from, to) = dateWindow(s.filter, s.cycleStartDay)
        return api().listTransactions(
            dateFrom = from, dateTo = to, categoryId = s.filter.categoryId, txType = s.filter.type,
            kind = s.filter.kind, q = s.filter.query.trim().ifEmpty { null },
            needsReview = if (s.filter.needsReviewOnly) true else null, limit = limit, offset = offset
        )
    }

    fun reloadCategories() {
        viewModelScope.launch {
            try {
                val cats = api().listCategories()
                com.ledger.app.data.DiskCache.put(getApplication<Application>(), "categories", cats)
                _state.value = _state.value.copy(categories = cats)
            } catch (_: Exception) {
            }
        }
    }

    /** First page again (pull-to-refresh, app resume, filter change). */
    fun refresh() {
        viewModelScope.launch {
            // "loading" while there is no list to keep showing: a filter change empties the list first, and an empty list
            // that is only waiting for the server must never read as "no records" (it used to, for a few seconds)
            val hasList = _state.value.transactions.isNotEmpty()
            val asked = _state.value.filter
            _state.value = _state.value.copy(loading = !hasList, error = null)
            try {
                // the list first - it is what is being waited for; the account details below used to come first
                val page = fetch(0, pageSize)
                if (_state.value.filter != asked) return@launch            // the filter changed meanwhile: a newer load is on its way
                _state.value = _state.value.copy(
                    loading = false, transactions = page, endReached = page.size < pageSize
                )
                val me = api().me()
                val categories = api().listCategories()
                val trips = try { api().listTrips().filter { it.kind == "business" } } catch (_: Exception) { emptyList() }
                _state.value = _state.value.copy(
                    inviteCode = me.invite_code, cycleStartDay = me.cycle_start_day, categories = categories, businessTrips = trips
                )
                val app = getApplication<Application>()
                com.ledger.app.data.DiskCache.put(app, "categories", categories)
                com.ledger.app.data.DiskCache.put(app, "invite", me.invite_code)
                com.ledger.app.data.DiskCache.put(app, "cycle_start", me.cycle_start_day)
                com.ledger.app.data.DiskCache.put(app, "business_trips", trips)
                if (!_state.value.filter.isActive) com.ledger.app.data.DiskCache.put(app, "tx_page", page)
                loadCycleSummary(me.cycle_start_day)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
    }

    /** Income / spending since the last payday (refunded purchases already netted out by the server). */
    private suspend fun loadCycleSummary(startDay: Int) {
        try {
            val today = LocalDate.now()
            var y = today.year
            var m = today.monthValue
            if (today.dayOfMonth < startDay) { m -= 1; if (m < 1) { m = 12; y -= 1 } }
            val inc = com.ledger.app.data.Prefs.getIncludeBusiness(getApplication())
            val sum = api().monthlySummary(y, m, inc)
            _state.value = _state.value.copy(cycleSummary = sum)
            com.ledger.app.data.DiskCache.put(getApplication<Application>(), "cycle_summary_" + inc, sum)
        } catch (_: Exception) {
        }
    }

    /** Next page, appended - called when the list scrolls near its end. */
    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || s.endReached) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingMore = true)
            try {
                val page = fetch(s.transactions.size, pageSize)
                _state.value = _state.value.copy(
                    loadingMore = false, transactions = _state.value.transactions + page,
                    endReached = page.size < pageSize
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loadingMore = false, error = e.message ?: "加载失败")
            }
        }
    }

    /** Reload everything currently shown without losing the scroll position (used after an edit). */
    private suspend fun reloadLoaded() {
        val n = maxOf(pageSize, _state.value.transactions.size).coerceAtMost(500)
        val page = fetch(0, n)
        _state.value = _state.value.copy(transactions = page, endReached = page.size < n)
        loadCycleSummary(_state.value.cycleStartDay)
    }

    fun setFilter(filter: TxFilter) {
        _state.value = _state.value.copy(filter = filter, transactions = emptyList(), endReached = false)
        refresh()
    }

    /** The refresh button / pull-down: pull anything still waiting on the phone (SMS, notifications), upload it, reload. */
    fun syncAndRefresh() {
        com.ledger.app.capture.SyncNow.run(getApplication())
        viewModelScope.launch {
            _state.value = _state.value.copy(syncing = true)
            delay(5000)
            refresh()
            _state.value = _state.value.copy(syncing = false)
        }
    }

    /** Long-press category change: the server also fixes the same merchant's other unconfirmed records and
     *  remembers the choice for future imports. */
    fun setCategory(id: Int, categoryId: Int, sub: String? = null, isTreat: Boolean = false) {
        viewModelScope.launch {
            try {
                val body = mutableMapOf<String, Any?>("category_id" to categoryId, "needs_review" to false, "is_treat" to isTreat)
                if (sub != null) body["sub"] = sub
                api().updateTransaction(id, body)
                reloadLoaded()
                _state.value = _state.value.copy(message = "已归类,同商户以后自动归到这一类")
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "修改失败")
            }
        }
    }

    /** The income the parser never flagged as a refund (no "退" in the SMS) is actually paired by hand with the
     *  purchase it cancels. */
    fun linkRefund(incomeId: Int, purchaseId: Int) {
        viewModelScope.launch {
            try {
                api().updateTransaction(incomeId, mapOf("refund_of_id" to purchaseId))
                reloadLoaded()
                _state.value = _state.value.copy(message = "已关联,这笔退款不再算收入")
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "关联失败")
            }
        }
    }

    /** The kind inside the category (clothes, snacks, hotel...). */
    fun setSub(id: Int, sub: String?) {
        viewModelScope.launch {
            try {
                api().updateTransaction(id, mapOf("sub" to (sub ?: "")))
                reloadLoaded()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "修改失败")
            }
        }
    }

    /** tripId = null takes the purchase out of its trip. */
    fun setBusinessTrip(tx: TransactionDto, tripId: Int?) {
        viewModelScope.launch {
            try {
                if (tripId == null) tx.trip_id?.let { api().removeFromTrip(it, tx.id) } else api().addToTrip(tripId, tx.id)
                reloadLoaded()
                _state.value = _state.value.copy(message = if (tripId == null) "已移出出差" else "已归入出差, 待报销")
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "操作失败")
            }
        }
    }

    /** First business purchase before any trip exists: make the trip and put the purchase into it, in one step. */
    fun newBusinessTripWith(tx: TransactionDto, name: String, start: String, end: String, daily: String?, commute: String?, autoTag: Boolean) {
        viewModelScope.launch {
            try {
                val trip = api().createTrip(com.ledger.app.data.TripInRequest(name, start, end, "business", daily, commute, autoTag))
                api().addToTrip(trip.id, tx.id)
                refresh()
                _state.value = _state.value.copy(message = "已建好「${name}」并归入, 待报销")
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "操作失败")
            }
        }
    }

    fun createManual(txType: String, amount: String, categoryId: Int?, note: String?) {
        viewModelScope.launch {
            try {
                api().createTransaction(TransactionCreateRequest(txType, amount, categoryId, note))
                _state.value = _state.value.copy(message = "已记录")
                refresh()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "记录失败")
            }
        }
    }

    /** Voice quick-add: parse the transcript, then immediately save it - no confirmation
     *  step, per the product decision that amount accuracy matters more than a review step. */
    fun voiceQuickAdd(transcript: String) {
        viewModelScope.launch {
            try {
                val parsed = api().parseTransaction(com.ledger.app.data.ParseRequestBody(transcript))
                val amount = parsed.amount
                if (amount == null) {
                    _state.value = _state.value.copy(error = "没听清金额,请手动记一笔")
                    return@launch
                }
                api().createTransaction(
                    TransactionCreateRequest(parsed.tx_type, amount, parsed.category_id, parsed.note)
                )
                val label = parsed.category_name ?: "未分类"
                _state.value = _state.value.copy(message = "已记录: $label ¥$amount")
                refresh()
            } catch (e: HttpException) {
                val msg = if (e.code() == 422) "没听清金额,请手动记一笔" else "记录失败 (${e.code()})"
                _state.value = _state.value.copy(error = msg)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "记录失败")
            }
        }
    }

    /** [previousCategoryId]: the category the record already has. The category is only sent when it really
     *  changed - sending it counts as "the user just filed this", which re-guesses the sub and re-files the
     *  shop's other payments, and a note edit must not do that. */
    fun updateTransaction(id: Int, txType: String, amount: String, categoryId: Int?, note: String?, previousCategoryId: Int? = null) {
        viewModelScope.launch {
            try {
                val body = mutableMapOf<String, Any?>("tx_type" to txType, "amount" to amount, "note" to note, "needs_review" to false)
                if (categoryId != previousCategoryId) body["category_id"] = categoryId
                api().updateTransaction(id, body)
                _state.value = _state.value.copy(message = "已更新")
                reloadLoaded()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "更新失败")
            }
        }
    }

    fun deleteTransaction(id: Int) {
        viewModelScope.launch {
            try {
                api().deleteTransaction(id)
                _state.value = _state.value.copy(message = "已删除")
                reloadLoaded()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "删除失败")
            }
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null, error = null)
    }
}
