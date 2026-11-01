"""Money owed to a person (the spouse who lent the money for a big purchase) and how much of it has been paid back.

A debt is a small JSON object stored on the ledger (ledgers.loans):
  name      what the app calls it ("欠老婆")
  who       the lender's name as it appears in the bank texts ("马方怡"); money from them after `start` is borrowed,
            money to them after `start` is paid back. Both are transfers (the spouse is family, see family_names), so
            neither counts as income or spending - the debt only moves between this list and the account balances.
  start     YYYY-MM-DD: only transfers on/after this day belong to the debt. Older transfers between the two of them
            (an earlier gift, an earlier repayment) are left alone.
  principal optional: a fixed amount instead of adding up what came in (e.g. if part of it came in cash)
  monthly   optional: what is meant to be paid back every month, for the "how many months left" line
"""
import json
import math
from datetime import date, datetime, time
from decimal import Decimal
from typing import Optional

from sqlalchemy.orm import Session

from app import models, schemas

ZERO = Decimal(0)


def load(ledger) -> list[dict]:
    try:
        items = json.loads(ledger.loans or "[]")
    except ValueError:
        return []
    return [i for i in items if isinstance(i, dict) and i.get("who")]


def _add_months(d: date, n: int) -> date:
    y, m = divmod(d.year * 12 + d.month - 1 + n, 12)
    return date(y, m + 1, 1)


def repaid_in(db: Session, ledger: models.Ledger, who: str, start: str, begin: datetime, end: datetime) -> Decimal:
    """What went back to `who` between `begin` and `end` (never before the debt's own start day)."""
    T = models.Transaction
    since = max(begin, datetime.combine(date.fromisoformat(start), time.min)) if start else begin
    rows = (db.query(T).filter(T.ledger_id == ledger.id, T.tx_type == models.TxType.expense, T.refund_of_id.is_(None),
                               T.merchant.contains(who), T.occurred_at >= since, T.occurred_at < end).all())
    return sum((t.amount for t in rows), ZERO)


def set_monthly(db: Session, ledger: models.Ledger, who: str, monthly: Optional[Decimal]) -> bool:
    """Change (or clear) the intended monthly repayment of the debt to `who`. False if there is no such debt."""
    items = load(ledger)
    hit = [i for i in items if i["who"] == who]
    if not hit:
        return False
    for i in hit:
        if monthly and monthly > 0:
            i["monthly"] = float(monthly)
        else:
            i.pop("monthly", None)
    ledger.loans = json.dumps(items, ensure_ascii=False)
    db.commit()
    return True


def status_for(db: Session, ledger: models.Ledger, today: date) -> list[schemas.LoanStatus]:
    from app.stats import current_cycle, cycle_bounds
    cy, cm = current_cycle(ledger.cycle_start_day or 1, today)
    cyc_start, cyc_end = cycle_bounds(ledger.cycle_start_day or 1, cy, cm)
    out = []
    T = models.Transaction
    for loan in load(ledger):
        who = loan["who"]
        start = date.fromisoformat(loan["start"]) if loan.get("start") else date(1970, 1, 1)
        rows = (db.query(T)
                .filter(T.ledger_id == ledger.id, T.occurred_at >= datetime.combine(start, datetime.min.time()),
                        T.refund_of_id.is_(None), T.merchant.contains(who))
                .order_by(T.occurred_at).all())
        came = [t for t in rows if t.tx_type == models.TxType.income]
        went = [t for t in rows if t.tx_type == models.TxType.expense]
        borrowed = Decimal(str(loan["principal"])) if loan.get("principal") else sum((t.amount for t in came), ZERO)
        repaid = sum((t.amount for t in went), ZERO)
        remaining = max(borrowed - repaid, ZERO)
        monthly = Decimal(str(loan["monthly"])) if loan.get("monthly") else None
        months_left = math.ceil(remaining / monthly) if (monthly and remaining > 0) else None
        first_of_month = today.replace(day=1)
        moves = sorted([(t.occurred_at, "borrow", t.amount) for t in came] + [(t.occurred_at, "repay", t.amount) for t in went], reverse=True)
        out.append(schemas.LoanStatus(
            name=loan.get("name") or f"欠{who}", who=who, start=start.isoformat(),
            borrowed=borrowed, repaid=repaid, remaining=remaining,
            percent=float((repaid / borrowed * 100).quantize(Decimal("0.1"))) if borrowed > 0 else 0.0,
            repaid_this_month=sum((t.amount for t in went if t.occurred_at.date() >= first_of_month), ZERO),
            repaid_this_cycle=sum((t.amount for t in went if cyc_start <= t.occurred_at < cyc_end), ZERO),
            monthly=monthly, months_left=months_left,
            payoff=_add_months(today, months_left).strftime("%Y-%m") if months_left else None,
            last_repaid=max((t.occurred_at for t in went), default=None).strftime("%Y-%m-%d") if went else None,
            recent=[schemas.LoanMove(date=d.strftime("%Y-%m-%d"), kind=k, amount=a) for d, k, a in moves[:6]],
        ))
    return out
