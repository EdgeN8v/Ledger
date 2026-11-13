"""Shared period maths for summaries, the savings plan and the analysis screens.

Rules every number in the app follows:
  * transfers between the user's own accounts are neither income nor spending;
  * a refund reduces the spending of the purchase it cancels (in the purchase's own period), so a returned item
    never counts - fully or partly; a refund with no matching purchase on record reduces spending in its own period;
  * income is real income only (salary, bonus, ...): refunds are not income.
"""
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from decimal import Decimal
from typing import Optional

from sqlalchemy import func
from sqlalchemy.orm import Session

from app import models


def cycle_bounds(start_day: int, year: int, month: int) -> tuple[datetime, datetime]:
    """[start, end) of the budget month that STARTS in (year, month) on start_day."""
    start = datetime(year, month, start_day)
    end = datetime(year + 1, 1, start_day) if month == 12 else datetime(year, month + 1, start_day)
    return start, end


def current_cycle(start_day: int, today: Optional[date] = None) -> tuple[int, int]:
    today = today or date.today()
    y, m = today.year, today.month
    if today.day < start_day:
        m -= 1
        if m < 1:
            y, m = y - 1, 12
    return y, m


def refunded_map(db: Session, ledger_id: int) -> dict[int, Decimal]:
    rows = (
        db.query(models.Transaction.refund_of_id, func.sum(models.Transaction.amount))
        .filter(models.Transaction.ledger_id == ledger_id, models.Transaction.refund_of_id.isnot(None))
        .group_by(models.Transaction.refund_of_id)
        .all()
    )
    return {rid: Decimal(total or 0) for rid, total in rows}


@dataclass
class PeriodData:
    start: datetime
    end: datetime
    income: Decimal = Decimal(0)
    expense: Decimal = Decimal(0)
    # (transaction, effective amount after refunds) for every purchase that still counts
    expenses: list = field(default_factory=list)
    incomes: list = field(default_factory=list)
    standalone_refunds: list = field(default_factory=list)


def period_data(db: Session, ledger_id: int, start: datetime, end: datetime) -> PeriodData:
    T = models.Transaction
    refunds = refunded_map(db, ledger_id)
    rows = (
        db.query(T)
        .filter(T.ledger_id == ledger_id, T.occurred_at >= start, T.occurred_at < end, T.is_transfer.is_(False))
        .all()
    )
    out = PeriodData(start=start, end=end)
    for t in rows:
        if t.refund_of_id:
            continue                      # accounted for inside its purchase
        if t.tx_type == models.TxType.expense:
            eff = t.amount - refunds.get(t.id, Decimal(0))
            if eff > 0:
                out.expenses.append((t, eff))
                out.expense += eff
        elif t.is_refund:
            out.standalone_refunds.append(t)
            out.expense -= t.amount       # money back for something bought before the data starts
        else:
            out.incomes.append(t)
            out.income += t.amount
    return out
