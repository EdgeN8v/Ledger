package com.ledger.app.data

// Field names deliberately mirror the backend's snake_case JSON exactly (see
// backend/app/schemas.py) so Gson can map them with zero annotations.

data class TokenResponse(
    val access_token: String,
    val token_type: String
)

data class RegisterRequest(
    val username: String,
    val password: String,
    val display_name: String,
    val ledger_name: String? = null
)

data class JoinRequest(
    val username: String,
    val password: String,
    val display_name: String,
    val invite_code: String
)

data class UserDto(
    val id: Int,
    val username: String,
    val display_name: String,
    val ledger_id: Int,
    val invite_code: String? = null,
    val cycle_start_day: Int = 1
)

data class CategoryDto(
    val id: Int,
    val name: String,
    val icon: String?,
    val tx_type: String,
    val keywords: String?,
    val sort_order: Int? = null,
    val recent_total: String? = null,
    val subs: List<String>? = null
)

data class SubTotalDto(val sub: String? = null, val total: String = "0", val count: Int = 0)

data class CategoryOrderRequest(val tx_type: String, val ids: List<Int>)

data class ParseRequestBody(
    val text: String,
    val source: String = "voice"
)

data class ParseResultDto(
    val tx_type: String,
    val amount: String?,
    val category_id: Int?,
    val category_name: String?,
    val note: String,
    val needs_review: Boolean
)

data class TransactionCreateRequest(
    val tx_type: String,
    val amount: String,
    val category_id: Int?,
    val note: String?
)

data class SummaryByCategoryDto(
    val category_id: Int?,
    val category_name: String,
    val tx_type: String,
    val total: String
)

data class MonthSummaryDto(
    val year: Int,
    val month: Int,
    val period_start: String? = null,
    val period_end: String? = null,
    val total_income: String,
    val total_expense: String,
    val by_category: List<SummaryByCategoryDto>
)

data class TransactionDto(
    val id: Int,
    val user_id: Int,
    val category_id: Int?,
    val tx_type: String,
    val amount: String,
    val note: String?,
    val merchant: String? = null,
    val channel: String? = null,
    val account_hint: String? = null,
    val sources: String? = null,
    val refund_of_id: Int? = null,
    val refunded_amount: String? = null,
    val is_reimbursement: Boolean = false,
    val trip_id: Int? = null,
    val trip_kind: String? = null,
    val trip_name: String? = null,
    val is_transfer: Boolean = false,
    val family_card: Boolean = false,
    val sub: String? = null,
    val is_treat: Boolean = false,
    val source: String,
    val raw_text: String?,
    val needs_review: Boolean,
    val occurred_at: String,
    val created_at: String
)

data class IngestRequest(
    val source: String,
    val title: String?,
    val text: String?,
    val posted_at_ms: Long,
    // which phone, and which Android notification: lets the server tell "the same notice again" / "the other
    // phone's copy" from "a new payment with the same text" (two orders of the same amount at the same shop)
    val device: String? = null,
    val notif_key: String? = null
)

data class IngestResult(
    val status: String,
    val event_id: Int? = null,
    val transaction: TransactionDto? = null,
    val amount: String? = null,
    val payee: String? = null,
    val suggested_category_id: Int? = null,
    val suggested_sub: String? = null
)

data class PendingCategoryRequest(val event_id: Int, val category_id: Int, val sub: String? = null, val note: String? = null)

data class AccountDto(
    val id: Int,
    val name: String,
    val kind: String,
    val balance: String?,
    val balance_at: String?,
    val include_in_total: Boolean
)

data class EarmarkedDto(
    val name: String,
    val amount: String,
    val cap: String,
    val due: String? = null,
    val last_paid: String? = null
)

data class AccountsOverviewDto(
    val receivable: String = "0",
    val receivable_trips: List<String> = emptyList(),
    val total: String,
    val accounts: List<AccountDto>,
    val earmarked: List<EarmarkedDto> = emptyList(),   // money in the accounts that is already spoken for (the rent fund ...)
    val earmarked_total: String = "0",
    val free_total: String? = null
)

data class AccountCreateRequest(
    val name: String,
    val kind: String = "bank",
    val balance: String? = null,
    val include_in_total: Boolean = true
)

data class PlanDto(
    val expected_income: String?,
    val savings_target: String?,
    val fixed_expenses: String?,
    val estimated_income: String,
    val treat_budget: String? = null
)

data class PlanInRequest(
    val expected_income: String?,
    val savings_target: String?,
    val fixed_expenses: String?,
    val treat_budget: String? = null
)

data class IncomeItemDto(val name: String, val amount: String)

data class PlanStatusDto(
    val year: Int,
    val month: Int,
    val period_start: String,
    val period_end: String,
    val days_total: Int,
    val days_elapsed: Int,
    val days_left: Int,
    val income_actual: String,
    val income_basis: String,
    val income_is_estimate: Boolean = false,                 // the cycle's pay is not in yet: the basis is a guess
    val income_items: List<IncomeItemDto> = emptyList(),     // what really arrived this cycle, by category
    val fixed_expenses: String,
    val savings_target: String,
    val fixed_total: String = "0",
    val fixed_items: List<FixedItemDto> = emptyList(),
    val spent_total: String = "0",
    val trip_total: String = "0",
    val budget: String,
    val spent: String,
    val remaining: String,
    val daily_allowance: String,
    val projected_spend: String,
    val projected_saving: String,
    val saved_so_far: String,
    val state: String,
    val treat_budget: String? = null,
    val treat_spent: String = "0",
    val treat_remaining: String? = null
)

data class TripBriefDto(val id: Int, val name: String, val total: String)

data class TripCategoryDto(val name: String, val icon: String?, val total: String)

data class TripDto(
    val id: Int, val name: String, val start_date: String, val end_date: String,
    val total: String, val count: Int, val by_category: List<TripCategoryDto>,
    val kind: String = "personal", val days: Int = 1, val flights: List<String> = emptyList(), val return_booked: String? = null, val open_end: Boolean = false, val long_trip: Boolean = false, val daily_allowance: String? = null,
    val commute_allowance: String? = null, val allowance_total: String = "0",
    val expected_reimbursement: String = "0", val settled: Boolean = false,
    val received_amount: String? = null, val allowance_gain: String? = null, val difference: String? = null
)

data class TripSuggestionDto(val start_date: String, val end_date: String, val reason: String, val estimate: String)

data class TripRefRequest(val start_date: String, val end_date: String)

data class TripInRequest(
    val name: String, val start_date: String, val end_date: String,
    val kind: String = "personal", val daily_allowance: String? = null, val commute_allowance: String? = null,
    val auto_tag: Boolean? = null           // null: keep as it is (edit) / on (new)
)

data class BillReminderDto(val name: String, val amount: String, val due_date: String, val days_left: Int, val note: String? = null)

data class ReceivableItemDto(val date: String, val merchant: String? = null, val amount: String)

data class ReceivableTripDto(
    val trip_id: Int, val name: String, val start_date: String, val end_date: String, val open_end: Boolean = false,
    val advanced: String, val allowance: String, val expected: String, val items: List<ReceivableItemDto>
)

data class SettleRequest(val transaction_id: Int)

data class FixedItemDto(
    val name: String,
    val reserve: String,
    val paid: String,
    val tracked: Boolean,
    val due_day: Int? = null,
    val note: String? = null,
    val paid_note: String? = null,
    val state: String,
    val pot: String? = null,            // sinking fund set aside so far for a lumpy bill (rent every 3 months)
    val pot_cap: String? = null,        // ... and what the next bill costs
    val pot_last_paid: String? = null,
    val pot_due: String? = null
)

data class FixedMarkRequest(val name: String, val paid: Boolean? = null, val paid_until: String? = null)

data class FixedHistoryEntryDto(val period_start: String, val period_end: String, val amount: String)

data class WeekStatDto(val start: String, val end: String, val total: String)

data class CategoryStatDto(
    val category_id: Int?,
    val name: String,
    val icon: String?,
    val total: String,
    val count: Int,
    val share: Double,
    val previous_total: String,
    val change_pct: Double?
)

data class ShopStatDto(val name: String, val total: String, val count: Int)

data class BigSpendDto(val id: Int, val category_id: Int? = null, val merchant: String, val amount: String, val date: String, val category: String)

data class DayStatDto(val date: String, val total: String)

data class CycleAnalysisDto(
    val family_total: String = "0",
    val year: Int,
    val month: Int,
    val period_start: String,
    val period_end: String,
    val total_expense: String,
    val total_income: String,
    val previous_expense: String,
    val avg_daily: String,
    val categories: List<CategoryStatDto>,
    val top_shops: List<ShopStatDto>,
    val biggest: List<BigSpendDto>,
    val daily: List<DayStatDto>,
    val weekday_totals: List<String>,
    val weekly: List<WeekStatDto> = emptyList(),
    val trips: List<TripBriefDto> = emptyList(),
    val trip_total: String = "0",
    val small_count: Int,
    val small_total: String,
    val insights: List<String>
)

data class CycleRowDto(
    val month: Int,
    val period_start: String,
    val period_end: String,
    val income: String,
    val expense: String,
    val saved: String
)

data class CarItemDto(val id: Int, val date: String, val merchant: String, val amount: String, val note: String? = null, val manual: Boolean = false)
data class CarChoiceDto(val key: String, val label: String)
data class CarBucketDto(val key: String, val label: String, val core: Boolean, val total: String, val count: Int, val items: List<CarItemDto>, val desc: String = "")
data class CarMonthDto(val month: String, val fuel: String, val repair: String, val other: String)
data class CarFuelDto(val count: Int, val total: String, val avg: String, val max: String)
data class CarReportDto(
    val since: String, val first_record: String, val range: String, val core_total: String, val all_total: String,
    val buckets: List<CarBucketDto>, val monthly: List<CarMonthDto>, val fuel: CarFuelDto, val choices: List<CarChoiceDto> = emptyList()
)

data class YearAnalysisDto(
    val year: Int,
    val income: String,
    val expense: String,
    val saved: String,
    val saving_rate: Double,
    val avg_monthly_expense: String,
    val months: List<CycleRowDto>,
    val categories: List<CategoryStatDto>,
    val best_month: Int?,
    val worst_month: Int?
)

data class ReportDto(
    val report_date: String,
    val generated_at: String,
    val headline: String,
    val period_start: String,
    val period_end: String,
    val yesterday_spend: String,
    val today_spend: String,
    val avg_daily: String,
    val plan_state: String,
    val remaining: String,
    val daily_allowance: String,
    val insights: List<String>,
    val alerts: List<String>,
    val subscriptions: List<String>
)

data class CareOdometerDto(val km: Int = 0, val as_of: String = "", val per_day: Double = 0.0, val est_km: Int = 0)
data class CareServiceDto(val id: Int = 0, val item: String = "", val name: String = "", val date: String = "", val km: Int? = null, val cost: String? = null, val note: String? = null)
data class CareItemDto(
    val key: String = "", val name: String = "", val interval_km: Int? = null, val interval_months: Int? = null, val note: String? = null,
    val last: CareServiceDto? = null, val due_km: Int? = null, val due_date: String? = null, val left_km: Int? = null, val left_days: Int? = null,
    val eta_date: String? = null, val status: String = "unknown"
)
data class CareChoiceDto(val key: String = "", val name: String = "")
data class CarCareDto(
    val odometer: CareOdometerDto? = null, val items: List<CareItemDto> = emptyList(),
    val history: List<CareServiceDto> = emptyList(), val choices: List<CareChoiceDto> = emptyList()
)

data class JdUnmatchedDto(val date: String = "", val amount: String = "", val title: String = "", val pay: String = "")
data class JdImportDto(
    val orders: Int = 0, val matched: Int = 0, val unmatched: Int = 0, val noted: Int = 0, val recategorized: Int = 0, val subbed: Int = 0,
    val unmatched_list: List<JdUnmatchedDto> = emptyList()
)
