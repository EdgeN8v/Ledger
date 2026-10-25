from datetime import datetime
from decimal import Decimal
from typing import Optional

from pydantic import BaseModel

from app.models import TxType, TxSource


class UserCreate(BaseModel):
    username: str
    password: str
    display_name: str
    ledger_name: Optional[str] = None  # set only when creating a brand-new ledger


class UserJoin(BaseModel):
    username: str
    password: str
    display_name: str
    invite_code: str  # random per-ledger code, shown in the owner's Settings screen


class UserOut(BaseModel):
    id: int
    username: str
    display_name: str
    ledger_id: int
    invite_code: Optional[str] = None
    cycle_start_day: int = 1

    class Config:
        from_attributes = True


class Token(BaseModel):
    access_token: str
    token_type: str = "bearer"


class CategoryCreate(BaseModel):
    name: str
    icon: Optional[str] = None
    tx_type: TxType = TxType.expense
    keywords: Optional[str] = None


class CategoryOrder(BaseModel):
    tx_type: TxType
    ids: list[int]


class CategoryOut(BaseModel):
    id: int
    name: str
    icon: Optional[str]
    tx_type: TxType
    keywords: Optional[str]
    sort_order: Optional[int] = None
    recent_total: Decimal = Decimal(0)     # spent (or earned) in the last 90 days: the default order
    subs: list[str] = []

    class Config:
        from_attributes = True


class TransactionCreate(BaseModel):
    tx_type: TxType = TxType.expense
    amount: Decimal
    category_id: Optional[int] = None
    note: Optional[str] = None
    occurred_at: Optional[datetime] = None


class TransactionUpdate(BaseModel):
    tx_type: Optional[TxType] = None
    amount: Optional[Decimal] = None
    category_id: Optional[int] = None
    note: Optional[str] = None
    merchant: Optional[str] = None
    occurred_at: Optional[datetime] = None
    needs_review: Optional[bool] = None
    car_bucket: Optional[str] = None
    sub: Optional[str] = None
    refund_of_id: Optional[int] = None   # the purchase this income cancels - for a refund the parser missed (no "退" in the SMS)
    is_treat: Optional[bool] = None


class TransactionOut(BaseModel):
    id: int
    user_id: int
    category_id: Optional[int]
    tx_type: TxType
    amount: Decimal
    note: Optional[str]
    merchant: Optional[str] = None
    channel: Optional[str] = None
    account_hint: Optional[str] = None
    sources: Optional[str] = None
    refund_of_id: Optional[int] = None
    refunded_amount: Decimal = Decimal(0)
    is_refund: bool = False
    is_reimbursement: bool = False
    trip_id: Optional[int] = None
    trip_kind: Optional[str] = None
    trip_name: Optional[str] = None
    is_transfer: bool = False
    family_card: bool = False
    car_bucket: Optional[str] = None
    sub: Optional[str] = None
    is_treat: bool = False
    source: TxSource
    raw_text: Optional[str]
    needs_review: bool
    occurred_at: datetime
    created_at: datetime

    class Config:
        from_attributes = True


class ParseRequest(BaseModel):
    text: str
    source: TxSource = TxSource.voice


class ParseResult(BaseModel):
    tx_type: TxType
    amount: Optional[Decimal]
    category_id: Optional[int]
    category_name: Optional[str]
    note: str
    needs_review: bool


class SummaryByCategory(BaseModel):
    category_id: Optional[int]
    category_name: str
    tx_type: TxType
    total: Decimal


class MonthSummary(BaseModel):
    year: int
    month: int
    period_start: Optional[str] = None
    period_end: Optional[str] = None
    total_income: Decimal
    total_expense: Decimal
    by_category: list[SummaryByCategory]


class IngestRequest(BaseModel):
    source: str                # package name or wechat/alipay/sms
    title: Optional[str] = None
    text: Optional[str] = None
    posted_at_ms: int          # when the phone received it (epoch ms)
    device: Optional[str] = None       # a fixed id per phone install - tells "the other phone's copy" from "a new payment"
    notif_key: Optional[str] = None    # Android's StatusBarNotification.key - the same key again = the same notice re-posted


class PendingCategoryIn(BaseModel):
    event_id: int
    category_id: int
    sub: Optional[str] = None
    note: Optional[str] = None


class IngestResult(BaseModel):
    status: str                # created / merged / duplicate / ignored / failed
    event_id: Optional[int] = None
    transaction: Optional[TransactionOut] = None
    amount: Optional[Decimal] = None    # set only when the phone should show its category popup: screen:* pages, and WeChat/Alipay payment notices (_popup_fields)
    payee: Optional[str] = None
    suggested_category_id: Optional[int] = None   # a learned shop rule for this payee, if there is one - the popup pre-selects it
    suggested_sub: Optional[str] = None


class RawEventOut(BaseModel):
    id: int
    source: str
    title: Optional[str]
    text: Optional[str]
    posted_at: datetime
    status: str

    class Config:
        from_attributes = True


class AccountCreate(BaseModel):
    name: str
    kind: str = "bank"            # bank / wallet / cash
    balance: Optional[Decimal] = None
    include_in_total: bool = True


class AccountUpdate(BaseModel):
    name: Optional[str] = None
    balance: Optional[Decimal] = None
    include_in_total: Optional[bool] = None


class AccountOut(BaseModel):
    id: int
    name: str
    kind: str
    balance: Optional[Decimal]
    balance_at: Optional[datetime]
    include_in_total: bool

    class Config:
        from_attributes = True


class ReceivableItem(BaseModel):
    date: str
    merchant: Optional[str] = None
    amount: Decimal


class ReceivableTrip(BaseModel):
    trip_id: int
    name: str
    start_date: str
    end_date: str
    open_end: bool = False
    advanced: Decimal                      # what you paid out (after refunds)
    allowance: Decimal                     # commute + daily x days
    expected: Decimal
    items: list[ReceivableItem]


class EarmarkedOut(BaseModel):
    name: str
    amount: Decimal               # set aside so far
    cap: Decimal                  # the bill it is for
    due: Optional[str] = None
    last_paid: Optional[str] = None


class AccountsOverview(BaseModel):
    receivable: Decimal = Decimal(0)   # business trips waiting to be paid back (not part of the total)
    receivable_trips: list[str] = []
    total: Decimal                # sum of balances of accounts counted in the total
    accounts: list[AccountOut]
    earmarked: list[EarmarkedOut] = []     # money in the accounts that is already spoken for (rent fund ...)
    earmarked_total: Decimal = Decimal(0)
    free_total: Optional[Decimal] = None   # total - earmarked_total: what is really free


class LedgerSettings(BaseModel):
    cycle_start_day: Optional[int] = None
    owner_names: Optional[str] = None
    family_names: Optional[str] = None


class PlanIn(BaseModel):
    expected_income: Optional[Decimal] = None
    savings_target: Optional[Decimal] = None
    fixed_expenses: Optional[Decimal] = None
    fixed_items: Optional[list[dict]] = None
    treat_budget: Optional[Decimal] = None


class PlanOut(BaseModel):
    expected_income: Optional[Decimal]
    savings_target: Optional[Decimal]
    fixed_expenses: Optional[Decimal]
    estimated_income: Decimal            # average of the last salaries, used when expected_income is blank
    fixed_items: list[dict] = []
    treat_budget: Optional[Decimal] = None


class BillReminder(BaseModel):
    name: str
    amount: Decimal
    due_date: str
    days_left: int
    note: Optional[str] = None


class FixedMark(BaseModel):
    name: str
    paid: Optional[bool] = None            # mark / unmark the CURRENT cycle as paid
    paid_until: Optional[str] = None       # YYYY-MM-DD paid in advance up to; "" clears it


class FixedHistoryEntry(BaseModel):
    period_start: str
    period_end: str
    amount: Decimal             # 0 when nothing matched that cycle


class IncomeItem(BaseModel):
    name: str
    amount: Decimal


class FixedItemStatus(BaseModel):
    name: str
    reserve: Decimal            # set aside every cycle
    paid: Decimal               # matching payments seen this cycle
    tracked: bool
    due_day: Optional[int] = None
    note: Optional[str] = None
    paid_note: Optional[str] = None
    state: str                  # paid / waiting / reserved
    pot: Optional[Decimal] = None          # sinking fund set aside so far for a lumpy bill (None = this item has none)
    pot_cap: Optional[Decimal] = None      # ... and what the next bill will cost
    pot_last_paid: Optional[str] = None    # YYYY-MM-DD of the payment the fund started after
    pot_due: Optional[str] = None          # YYYY-MM-DD the next bill falls due (paid-in-advance date), if known


class PlanStatus(BaseModel):
    year: int
    month: int
    period_start: str
    period_end: str
    days_total: int
    days_elapsed: int
    days_left: int
    income_actual: Decimal
    income_basis: Decimal
    income_is_estimate: bool = False     # True: the cycle's pay is not in yet (or is below the typed-in floor), basis is a guess
    income_items: list[IncomeItem] = []  # what really arrived this cycle, by category (工资 / 公积金 ...)
    fixed_expenses: Decimal
    savings_target: Decimal
    budget: Decimal                      # what may be spent freely this cycle
    spent: Decimal
    remaining: Decimal
    daily_allowance: Decimal
    projected_spend: Decimal
    projected_saving: Decimal
    saved_so_far: Decimal
    state: str                           # no_plan / on_track / over
    fixed_total: Decimal = Decimal(0)
    fixed_items: list[FixedItemStatus] = []
    spent_total: Decimal = Decimal(0)    # everything spent, fixed items included
    trip_total: Decimal = Decimal(0)     # trips: really spent, but not extrapolated into the pace forecast
    treat_budget: Optional[Decimal] = None
    treat_spent: Decimal = Decimal(0)
    treat_remaining: Optional[Decimal] = None


class CategoryStat(BaseModel):
    category_id: Optional[int]
    name: str
    icon: Optional[str]
    total: Decimal
    count: int
    share: float
    previous_total: Decimal
    change_pct: Optional[float]


class ShopStat(BaseModel):
    name: str
    total: Decimal
    count: int


class BigSpend(BaseModel):
    id: int
    category_id: Optional[int] = None
    merchant: str
    amount: Decimal
    date: str
    category: str


class DayStat(BaseModel):
    date: str
    total: Decimal


class WeekStat(BaseModel):
    start: str
    end: str
    total: Decimal


class TripBrief(BaseModel):
    id: int
    name: str
    total: Decimal


class CycleAnalysis(BaseModel):
    year: int
    month: int
    period_start: str
    period_end: str
    total_expense: Decimal
    total_income: Decimal
    previous_expense: Decimal
    avg_daily: Decimal
    categories: list[CategoryStat]
    top_shops: list[ShopStat]
    biggest: list[BigSpend]
    daily: list[DayStat]
    weekday_totals: list[Decimal]
    weekly: list[WeekStat] = []
    trips: list[TripBrief] = []
    trip_total: Decimal = Decimal(0)
    small_count: int
    small_total: Decimal
    insights: list[str]
    family_total: Decimal = Decimal(0)     # of the total, what was paid with the family card


class CycleRow(BaseModel):
    month: int
    period_start: str
    period_end: str
    income: Decimal
    expense: Decimal
    saved: Decimal


class YearAnalysis(BaseModel):
    year: int
    income: Decimal
    expense: Decimal
    saved: Decimal
    saving_rate: float
    avg_monthly_expense: Decimal
    months: list[CycleRow]
    categories: list[CategoryStat]
    best_month: Optional[int]
    worst_month: Optional[int]


class ReportOut(BaseModel):
    report_date: str
    generated_at: datetime
    headline: str
    period_start: str
    period_end: str
    yesterday_spend: Decimal
    today_spend: Decimal
    avg_daily: Decimal
    plan_state: str
    remaining: Decimal
    daily_allowance: Decimal
    insights: list[str]
    alerts: list[str]
    subscriptions: list[str]


class TripIn(BaseModel):
    name: str
    start_date: str
    end_date: str
    kind: str = "personal"                 # personal / business
    auto_tag: Optional[bool] = None        # None = keep as is on update, on for a new trip
    daily_allowance: Optional[Decimal] = None
    commute_allowance: Optional[Decimal] = None


class TripCategory(BaseModel):
    name: str
    icon: Optional[str]
    total: Decimal


class TripOut(BaseModel):
    id: int
    name: str
    start_date: str
    end_date: str
    total: Decimal
    count: int
    by_category: list[TripCategory]
    kind: str = "personal"
    days: int = 1
    flights: list[str] = []
    return_booked: Optional[str] = None   # day the ticket home was issued, when only that is known (the date is not in the SMS)
    open_end: bool = False        # the way back is not booked yet: the end date is a placeholder, no allowance is counted
    long_trip: bool = False       # a month or more: allowances are paid on the employer's own schedule, not counted here
    daily_allowance: Optional[Decimal] = None
    commute_allowance: Optional[Decimal] = None
    allowance_total: Decimal = Decimal(0)
    expected_reimbursement: Decimal = Decimal(0)   # advanced money + allowances
    settled: bool = False
    received_amount: Optional[Decimal] = None
    allowance_gain: Optional[Decimal] = None   # what counts as income: the allowances
    difference: Optional[Decimal] = None      # paid back minus (advanced + allowances): negative = still owed


class TripSuggestion(BaseModel):
    start_date: str
    end_date: str
    reason: str
    estimate: Decimal


class TripSuggestionRef(BaseModel):
    start_date: str
    end_date: str


class SettleIn(BaseModel):
    transaction_id: int
