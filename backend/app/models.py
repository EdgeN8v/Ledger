import enum
import secrets

from sqlalchemy import (
    Column, Integer, String, Numeric, DateTime, Date, ForeignKey, Enum, Boolean, Text, Index
)
from sqlalchemy.orm import relationship

from app.clock import now_local
from app.database import Base


class TxType(str, enum.Enum):
    expense = "expense"
    income = "income"


class TxSource(str, enum.Enum):
    manual = "manual"
    voice = "voice"
    notification = "notification"
    sms = "sms"


def _invite_code() -> str:
    return secrets.token_urlsafe(6)


class Ledger(Base):
    __tablename__ = "ledgers"

    id = Column(Integer, primary_key=True)
    name = Column(String, nullable=False, default="家庭账本")
    invite_code = Column(String, unique=True, nullable=False, default=_invite_code)
    cycle_start_day = Column(Integer, nullable=False, default=1)   # budget month starts on this day (payday)
    expected_income = Column(Numeric(14, 2), nullable=True)   # planned monthly income (blank = estimate from salary SMS)
    savings_target = Column(Numeric(14, 2), nullable=True)    # how much to keep every pay cycle
    fixed_expenses = Column(Numeric(14, 2), nullable=True)    # rent etc. paid from accounts that send no SMS
    fixed_items = Column(Text, nullable=True)      # JSON list of fixed commitments, see fixed.py
    home_city = Column(String, nullable=True)      # where business trips start and end (default 上海)
    owner_names = Column(String, nullable=True)    # comma-separated names of the account holders: money from/to them is a transfer
    created_at = Column(DateTime, default=now_local)

    members = relationship("User", back_populates="ledger")
    categories = relationship("Category", back_populates="ledger")
    transactions = relationship("Transaction", back_populates="ledger")


class User(Base):
    __tablename__ = "users"

    id = Column(Integer, primary_key=True)
    username = Column(String, unique=True, nullable=False, index=True)
    display_name = Column(String, nullable=False)
    password_hash = Column(String, nullable=False)
    ledger_id = Column(Integer, ForeignKey("ledgers.id"), nullable=False)
    created_at = Column(DateTime, default=now_local)

    ledger = relationship("Ledger", back_populates="members")

    @property
    def invite_code(self):
        return self.ledger.invite_code if self.ledger else None

    @property
    def cycle_start_day(self):
        return self.ledger.cycle_start_day if self.ledger else 1


class Category(Base):
    __tablename__ = "categories"

    id = Column(Integer, primary_key=True)
    ledger_id = Column(Integer, ForeignKey("ledgers.id"), nullable=False)
    name = Column(String, nullable=False)
    icon = Column(String, nullable=True)
    tx_type = Column(Enum(TxType), nullable=False, default=TxType.expense)
    keywords = Column(String, nullable=True)  # comma-separated hints for the rule-based parser
    sort_order = Column(Integer, nullable=True)   # the user's own order (drag to move); None = by recent spending

    ledger = relationship("Ledger", back_populates="categories")


class Transaction(Base):
    __tablename__ = "transactions"

    id = Column(Integer, primary_key=True)
    ledger_id = Column(Integer, ForeignKey("ledgers.id"), nullable=False)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    category_id = Column(Integer, ForeignKey("categories.id"), nullable=True)

    tx_type = Column(Enum(TxType), nullable=False, default=TxType.expense)
    amount = Column(Numeric(12, 2), nullable=False)
    note = Column(String, nullable=True)
    merchant = Column(String, nullable=True)
    channel = Column(String, nullable=True)        # bank / wechat / alipay / cash
    account_hint = Column(String, nullable=True)   # e.g. 某银行1234
    sources = Column(String, nullable=True)        # comma-separated origins merged into this record
    refund_of_id = Column(Integer, ForeignKey("transactions.id"), nullable=True)  # set on a refund: the expense it cancels
    is_refund = Column(Boolean, default=False)     # money coming back from a return/refund (not income)
    trip_manual = Column(Boolean, default=False)       # the user put it into the trip by hand (survives date edits)
    is_reimbursement = Column(Boolean, default=False)   # income that is the employer paying a business trip back
    trip_id = Column(Integer, ForeignKey("trips.id"), nullable=True)   # part of a trip: kept out of the daily pace forecast
    is_transfer = Column(Boolean, default=False)   # money moved between the user's own accounts: not income/expense
    category_locked = Column(Boolean, nullable=False, default=False)   # the user chose the category: nothing re-files it

    source = Column(Enum(TxSource), nullable=False, default=TxSource.manual)
    raw_text = Column(Text, nullable=True)  # original voice transcript / notification / sms text
    needs_review = Column(Boolean, default=False)  # low-confidence parse, flag for the review list

    occurred_at = Column(DateTime, default=now_local, index=True)
    created_at = Column(DateTime, default=now_local)

    ledger = relationship("Ledger", back_populates="transactions")
    user = relationship("User")
    category = relationship("Category")


class RawEvent(Base):
    """Every notification/SMS the phones forward, kept verbatim. Gives idempotent re-sends and a
    'failed' list that new parser templates are written from."""
    __tablename__ = "raw_events"

    id = Column(Integer, primary_key=True)
    ledger_id = Column(Integer, ForeignKey("ledgers.id"), nullable=False)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    fingerprint = Column(String, nullable=False)
    source = Column(String, nullable=False)        # wechat / alipay / sms
    title = Column(String, nullable=True)
    text = Column(Text, nullable=True)
    posted_at = Column(DateTime, nullable=False)
    status = Column(String, nullable=False)        # created / merged / ignored / failed
    transaction_id = Column(Integer, ForeignKey("transactions.id"), nullable=True)
    created_at = Column(DateTime, default=now_local)

    __table_args__ = (Index("ix_raw_events_ledger_fp", "ledger_id", "fingerprint", unique=True),)


class MerchantRule(Base):
    """Learned from the user's corrections: this merchant is always that category."""
    __tablename__ = "merchant_rules"

    id = Column(Integer, primary_key=True)
    ledger_id = Column(Integer, ForeignKey("ledgers.id"), nullable=False)
    pattern = Column(String, nullable=False)
    category_id = Column(Integer, ForeignKey("categories.id"), nullable=False)

    __table_args__ = (Index("ix_merchant_rules_ledger_pattern", "ledger_id", "pattern", unique=True),)


class Account(Base):
    """A place money sits: a bank card, a wallet, cash. Bank SMS keep the balance current by themselves."""
    __tablename__ = "accounts"

    id = Column(Integer, primary_key=True)
    ledger_id = Column(Integer, ForeignKey("ledgers.id"), nullable=False)
    name = Column(String, nullable=False)          # e.g. 某银行1234 - matches Transaction.account_hint
    kind = Column(String, nullable=False, default="bank")   # bank / wallet / cash
    balance = Column(Numeric(14, 2), nullable=True)
    balance_at = Column(DateTime, nullable=True)   # when the balance was last confirmed
    include_in_total = Column(Boolean, default=True)
    created_at = Column(DateTime, default=now_local)

    __table_args__ = (Index("ix_accounts_ledger_name", "ledger_id", "name", unique=True),)


class AnalysisReport(Base):
    """The daily analysis the server writes by itself (see reports.py); the app only reads the latest one."""
    __tablename__ = "analysis_reports"

    id = Column(Integer, primary_key=True)
    ledger_id = Column(Integer, ForeignKey("ledgers.id"), nullable=False)
    report_date = Column(String, nullable=False)          # YYYY-MM-DD, one report per ledger per day
    generated_at = Column(DateTime, default=now_local)
    headline = Column(String, nullable=False)
    payload = Column(Text, nullable=False)               # JSON

    __table_args__ = (Index("ix_reports_ledger_date", "ledger_id", "report_date", unique=True),)


class Trip(Base):
    __tablename__ = "trips"

    id = Column(Integer, primary_key=True)
    ledger_id = Column(Integer, ForeignKey("ledgers.id"), nullable=False)
    name = Column(String, nullable=False)
    start_date = Column(String, nullable=False)    # YYYY-MM-DD
    end_date = Column(String, nullable=False)
    kind = Column(String, nullable=False, default="personal")      # personal / business
    auto_tag = Column(Boolean, nullable=False, default=True)      # pull in everything bought inside the date range
    daily_allowance = Column(Numeric(12, 2), nullable=True)       # business: per-day allowance owed by the employer
    commute_allowance = Column(Numeric(12, 2), nullable=True)     # business: one-off commuting allowance
    settled_at = Column(DateTime, nullable=True)                  # business: when the employer paid it back
    received_amount = Column(Numeric(14, 2), nullable=True)
    reimbursement_tx_id = Column(Integer, nullable=True)
    auto_key = Column(String, nullable=True)                      # made from flight SMS: first flight of the journey
    return_booked_on = Column(String, nullable=True)              # a ticket back home was issued on this day, its date unknown
    open_end = Column(Boolean, nullable=False, default=False)     # flights say the way back is not known yet
    dates_locked = Column(Boolean, nullable=False, default=False)  # the user edited the dates: flights no longer move them
    created_at = Column(DateTime, default=now_local)


class TripDismissal(Base):
    """A range the user said is NOT a trip, so it is not suggested again."""
    __tablename__ = "trip_dismissals"

    id = Column(Integer, primary_key=True)
    ledger_id = Column(Integer, ForeignKey("ledgers.id"), nullable=False)
    start_date = Column(String, nullable=False)
    end_date = Column(String, nullable=False)


class Flight(Base):
    """One flight leg of a ticket SMS. Not money: only payments become transactions."""
    __tablename__ = "flights"

    id = Column(Integer, primary_key=True)
    ledger_id = Column(Integer, ForeignKey("ledgers.id"), nullable=False)
    trip_id = Column(Integer, nullable=True)
    passenger = Column(String, nullable=True)
    flight_no = Column(String, nullable=False)
    origin = Column(String, nullable=False)       # city
    dest = Column(String, nullable=False)
    depart_at = Column(DateTime, nullable=False)
    arrive_at = Column(DateTime, nullable=False)
    ordered_on = Column(Date, nullable=False)
    ticket_no = Column(String, nullable=True)
    price = Column(Numeric(12, 2), nullable=True)  # whole order, when the SMS says so
