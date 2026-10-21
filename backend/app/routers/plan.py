"""The savings plan: how much to keep each pay cycle, and where the cycle stands against it."""
from datetime import date, timedelta
from decimal import Decimal
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app import models, schemas
from app.database import get_db
from app.deps import get_current_user
import json

from app.fixed import cycle_key, item_for, load, marked_paid, matches, reminders
from app.clock import now_local
from app.stats import current_cycle, cycle_bounds, period_data

router = APIRouter(prefix="/plan", tags=["plan"])

ZERO = Decimal(0)


def _salary_estimate(db: Session, ledger: models.Ledger) -> Decimal:
    """Planning figure for a variable salary: the LOWEST of the last three real salaries - a cautious baseline, so a
    good month never inflates the budget and a bad one never breaks it."""
    cats = {c.id for c in db.query(models.Category).filter(models.Category.ledger_id == ledger.id, models.Category.name == "工资")}
    rows = (
        db.query(models.Transaction)
        .filter(models.Transaction.ledger_id == ledger.id, models.Transaction.category_id.in_(cats or {-1}),
                models.Transaction.tx_type == models.TxType.income, models.Transaction.is_transfer.is_(False),
                models.Transaction.raw_text.contains("工资"))
        .order_by(models.Transaction.occurred_at.desc())
        .limit(3)
        .all()
    )
    return min((r.amount for r in rows), default=ZERO).quantize(Decimal("0.01"))


@router.get("", response_model=schemas.PlanOut)
def get_plan(db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    l = user.ledger
    est = _salary_estimate(db, l)
    return schemas.PlanOut(
        expected_income=l.expected_income, savings_target=l.savings_target, fixed_expenses=l.fixed_expenses,
        estimated_income=est, fixed_items=load(l),
    )


@router.get("/reminders", response_model=list[schemas.BillReminder])
def bill_reminders(db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    """Fixed bills that are due by the end of this month and have not been paid yet (shown 5 days ahead)."""
    l = user.ledger
    today = now_local().date()
    first = today.replace(day=1)
    rows = (
        db.query(models.Transaction)
        .filter(models.Transaction.ledger_id == l.id, models.Transaction.tx_type == models.TxType.expense,
                models.Transaction.is_transfer.is_(False), models.Transaction.occurred_at >= first)
        .all()
    )

    def paid(item) -> bool:
        return any(matches(item, t.amount, t.merchant, t.raw_text or "", None) for t in rows)

    y, m = current_cycle(l.cycle_start_day or 1, today)
    return reminders(load(l), today, paid, cycle_key(y, m))


@router.get("/fixed-history", response_model=list[schemas.FixedHistoryEntry])
def fixed_history(name: str, cycles: int = 6, db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    """What a named fixed commitment (e.g. a utility bill with no fixed amount) actually cost over its last few
    cycles, oldest first - so the user can see the swings behind a reserve figure that is "the latest cycle", not
    a single confirmed number."""
    l = user.ledger
    items = load(l)
    it = next((i for i in items if i["name"] == name), None)
    if it is None:
        raise HTTPException(404, "no such fixed item")
    start_day = l.cycle_start_day or 1
    year, month = current_cycle(start_day)
    cycles = min(max(cycles, 1), 12)
    out = []
    for _ in range(cycles):
        year, month = (year - 1, 12) if month == 1 else (year, month - 1)
        start, end = cycle_bounds(start_day, year, month)
        d = period_data(db, l.id, start, end)
        got = sum((e for t, e in d.expenses if (item_for(items, t.amount, t.merchant, t.raw_text or "", t.occurred_at) or {}).get("name") == name), ZERO)
        out.append(schemas.FixedHistoryEntry(
            period_start=start.strftime("%Y-%m-%d"), period_end=(end - timedelta(days=1)).strftime("%Y-%m-%d"), amount=got,
        ))
    out.reverse()
    return out


@router.put("", response_model=schemas.PlanOut)
def set_plan(
    payload: schemas.PlanIn,
    db: Session = Depends(get_db),
    user: models.User = Depends(get_current_user),
):
    l = user.ledger
    for field, value in payload.model_dump(exclude_unset=True).items():
        setattr(l, field, json.dumps(value, ensure_ascii=False) if field == "fixed_items" else value)
    db.commit()
    return get_plan(db, user)


@router.post("/fixed-mark", response_model=schemas.PlanOut)
def mark_fixed(payload: schemas.FixedMark, db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    """The user says: this commitment is paid for the current cycle (it left no trace in the SMS), or paid ahead up to a date."""
    l = user.ledger
    items = load(l)
    it = next((i for i in items if i["name"] == payload.name), None)
    if it is None:
        raise HTTPException(404, "no such fixed item")
    y, m = current_cycle(l.cycle_start_day or 1)
    key = cycle_key(y, m)
    cycles = [c for c in (it.get("paid_cycles") or []) if c != key]
    if payload.paid:
        cycles.append(key)
    it["paid_cycles"] = sorted(cycles)[-6:]
    if payload.paid_until is not None:
        if payload.paid_until:
            date.fromisoformat(payload.paid_until)
            it["paid_until"] = payload.paid_until
        else:
            it.pop("paid_until", None)
    l.fixed_items = json.dumps(items, ensure_ascii=False)
    db.commit()
    return get_plan(db, user)


@router.get("/status", response_model=schemas.PlanStatus)
def plan_status(
    year: Optional[int] = None,
    month: Optional[int] = None,
    db: Session = Depends(get_db),
    user: models.User = Depends(get_current_user),
):
    return status_for(db, user.ledger, year, month)


def status_for(db: Session, l: models.Ledger, year: Optional[int] = None, month: Optional[int] = None) -> schemas.PlanStatus:
    start_day = l.cycle_start_day or 1
    if not year or not month:
        year, month = current_cycle(start_day)
    start, end = cycle_bounds(start_day, year, month)
    data = period_data(db, l.id, start, end)

    today = date.today()
    in_cycle = start.date() <= today < end.date()
    total_days = (end - start).days
    elapsed = min(max((today - start.date()).days + 1, 1), total_days) if in_cycle else (total_days if today >= end.date() else 0)
    left = max(total_days - elapsed, 0)

    expected = l.expected_income if l.expected_income else _salary_estimate(db, l)
    target = l.savings_target or ZERO
    items = load(l)

    paid = {it["name"]: ZERO for it in items}
    for t, eff in data.expenses:
        it = item_for(items, t.amount, t.merchant, t.raw_text or "", t.occurred_at)
        if it is not None:
            paid[it["name"]] += eff                 # whatever matches is a fixed payment, seen or not

    def reserve(it) -> Decimal:
        if it.get("monthly"):
            return Decimal(str(it["monthly"]))
        # amount unknown (utilities): use the last cycle that actually had a bill, so a sudden jump this winter
        # shows up right away instead of being smoothed out by older, cheaper cycles - fall further back only
        # if the most recent cycles are missing a bill entirely (e.g. not imported yet)
        py, pm = year, month
        for _ in range(6):
            py, pm = (py - 1, 12) if pm == 1 else (py, pm - 1)
            ps, pe = cycle_bounds(start_day, py, pm)
            d = period_data(db, l.id, ps, pe)
            got = sum((e for t, e in d.expenses if (item_for(items, t.amount, t.merchant, t.raw_text or "", t.occurred_at) or {}).get("name") == it["name"]), ZERO)
            if got:
                return got.quantize(Decimal("0.01"))
        return ZERO

    fixed_rows, fixed_total, matched_total = [], ZERO, ZERO
    for it in items:
        r = reserve(it)
        fixed_total += r
        tracked = bool(it.get("tracked", True))
        matched_total += paid[it["name"]]
        why = None if paid[it["name"]] > 0 else marked_paid(it, cycle_key(year, month), today if in_cycle else None)
        fixed_rows.append(schemas.FixedItemStatus(
            name=it["name"], reserve=r, paid=paid[it["name"]], tracked=tracked, due_day=it.get("due_day"), note=it.get("note"),
            paid_note=why,
            state="paid" if (paid[it["name"]] > 0 or why) else ("waiting" if tracked else "reserved"),
        ))
    if not items:                                    # no itemised commitments: the old single "fixed expenses" figure
        fixed_total = l.fixed_expenses or ZERO

    # until payday the real income is not in yet: plan with the expected one, afterwards with whichever is larger
    # salary varies: once this cycle's pay has landed use what really arrived; before that use the cautious
    # estimate. A figure typed by the user is only a floor (a raise shows up as soon as it is paid).
    if l.expected_income:
        income_basis = max(l.expected_income, data.income)
    else:
        income_basis = data.income if data.income > 0 else expected
    budget = income_basis - fixed_total - target                 # what may be spent freely this cycle
    trip_total = sum((e for t, e in data.expenses if t.trip_id and not item_for(items, t.amount, t.merchant, t.raw_text or "", t.occurred_at)), ZERO)
    spent = data.expense - matched_total - trip_total            # everyday spending only (fixed items and trips are separate)
    remaining = budget - spent - trip_total                      # ...but a trip is real money, so it does come off what is left
    # forecast: extrapolate the everyday pace only; a trip is a one-off that happened, not a habit
    pace = ((spent / elapsed * total_days) if elapsed else ZERO) + trip_total
    projected_save = income_basis - fixed_total - pace
    saved_so_far = income_basis - fixed_total - spent
    allowance = (remaining / left) if left > 0 and remaining > 0 else ZERO

    if not l.savings_target:
        state = "no_plan"
    elif spent > budget:
        state = "over"
    elif pace > budget:
        state = "at_risk"
    else:
        state = "on_track"

    return schemas.PlanStatus(
        year=year, month=month, period_start=start.strftime("%Y-%m-%d"),
        period_end=(end - timedelta(days=1)).strftime("%Y-%m-%d"),
        days_total=total_days, days_elapsed=elapsed, days_left=left,
        income_actual=data.income, income_basis=income_basis, fixed_expenses=fixed_total, savings_target=target,
        budget=budget, spent=spent, remaining=remaining, daily_allowance=allowance.quantize(Decimal("0.01")),
        projected_spend=pace.quantize(Decimal("0.01")), projected_saving=projected_save.quantize(Decimal("0.01")),
        saved_so_far=saved_so_far, state=state, fixed_total=fixed_total, fixed_items=fixed_rows, spent_total=data.expense, trip_total=trip_total,
    )
