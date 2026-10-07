from datetime import datetime, timedelta
from decimal import Decimal
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import extract, func
from sqlalchemy.orm import Session

from app import models, schemas
from app.database import get_db
from app.clock import now_local
from app.deps import get_current_user
from app.merchants import shop_core
from app.refunds import refunded_amount
from app.stats import cycle_bounds, period_data
from app.parsing import parse_transaction, ParseError

router = APIRouter(prefix="/transactions", tags=["transactions"])


@router.post("/parse", response_model=schemas.ParseResult)
def parse(
    payload: schemas.ParseRequest,
    db: Session = Depends(get_db),
    user: models.User = Depends(get_current_user),
):
    """Turns raw text (voice transcript / notification / sms) into a transaction preview.
    Does NOT persist anything - caller reviews/edits then POSTs to /transactions."""
    try:
        amount, category, note, needs_review = parse_transaction(db, user.ledger_id, payload.text)
    except ParseError:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "无法识别金额,请手动记录")

    return schemas.ParseResult(
        tx_type=category.tx_type if category else models.TxType.expense,
        amount=amount,
        category_id=category.id if category else None,
        category_name=category.name if category else None,
        note=note,
        needs_review=needs_review,
    )


@router.post("", response_model=schemas.TransactionOut)
def create_transaction(
    payload: schemas.TransactionCreate,
    db: Session = Depends(get_db),
    user: models.User = Depends(get_current_user),
):
    tx = models.Transaction(
        ledger_id=user.ledger_id,
        user_id=user.id,
        category_id=payload.category_id,
        tx_type=payload.tx_type,
        amount=payload.amount,
        note=payload.note,
        occurred_at=payload.occurred_at or now_local(),
        source=models.TxSource.manual,
    )
    db.add(tx)
    db.commit()
    db.refresh(tx)
    return tx


@router.get("", response_model=list[schemas.TransactionOut])
def list_transactions(
    year: Optional[int] = None,
    month: Optional[int] = None,
    date_from: Optional[str] = None,       # YYYY-MM-DD, inclusive
    date_to: Optional[str] = None,         # YYYY-MM-DD, inclusive
    category_id: Optional[int] = None,
    tx_type: Optional[str] = None,         # expense / income
    kind: Optional[str] = None,            # transfer = only transfers, normal = hide transfers
    q: Optional[str] = None,               # substring of merchant / note / message text
    needs_review: Optional[bool] = None,
    trip_id: Optional[int] = None,
    sort: str = "time_desc",              # time_desc / time_asc / amount_desc / amount_asc
    limit: int = 50,
    offset: int = 0,
    db: Session = Depends(get_db),
    user: models.User = Depends(get_current_user),
):
    """Newest first, paged with limit/offset so the app can keep loading as the history grows."""
    T = models.Transaction
    query = db.query(T).filter(T.ledger_id == user.ledger_id)
    if year:
        query = query.filter(extract("year", T.occurred_at) == year)
    if month:
        query = query.filter(extract("month", T.occurred_at) == month)
    if date_from:
        query = query.filter(T.occurred_at >= datetime.strptime(date_from, "%Y-%m-%d"))
    if date_to:
        query = query.filter(T.occurred_at < datetime.strptime(date_to, "%Y-%m-%d") + timedelta(days=1))
    if category_id:
        query = query.filter(T.category_id == category_id)
    if tx_type in ("expense", "income"):
        query = query.filter(T.tx_type == models.TxType(tx_type))
    if kind == "transfer":
        query = query.filter(T.is_transfer.is_(True))
    elif kind == "normal":
        query = query.filter(T.is_transfer.is_(False))
    if q:
        like = f"%{q.strip()}%"
        query = query.filter(T.merchant.ilike(like) | T.note.ilike(like) | T.raw_text.ilike(like))
    if needs_review is not None:
        query = query.filter(T.needs_review == needs_review)
    if trip_id:
        query = query.filter(T.trip_id == trip_id)
    order = {
        "time_asc": (T.occurred_at.asc(), T.id.asc()),
        "amount_desc": (T.amount.desc(), T.occurred_at.desc()),
        "amount_asc": (T.amount.asc(), T.occurred_at.desc()),
    }.get(sort, (T.occurred_at.desc(), T.id.desc()))
    rows = query.order_by(*order).offset(max(offset, 0)).limit(min(max(limit, 1), 500)).all()
    for t in rows:
        t.refunded_amount = refunded_amount(db, t.id) if t.tx_type == models.TxType.expense else Decimal(0)
    return rows


@router.put("/{tx_id}", response_model=schemas.TransactionOut)
def update_transaction(
    tx_id: int,
    payload: schemas.TransactionUpdate,
    db: Session = Depends(get_db),
    user: models.User = Depends(get_current_user),
):
    tx = (
        db.query(models.Transaction)
        .filter(models.Transaction.id == tx_id, models.Transaction.ledger_id == user.ledger_id)
        .first()
    )
    if not tx:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "transaction not found")

    changes = payload.model_dump(exclude_unset=True)
    for field, value in changes.items():
        setattr(tx, field, value)

    # correcting a category once teaches the server: that shop goes into this category from now on, however it is paid
    core = shop_core(tx.merchant)
    if "category_id" in changes and changes["category_id"] and len(core) >= 2:
        rule = (
            db.query(models.MerchantRule)
            .filter(models.MerchantRule.ledger_id == user.ledger_id, models.MerchantRule.pattern == core)
            .first()
        )
        if rule:
            rule.category_id = changes["category_id"]
        else:
            db.add(models.MerchantRule(ledger_id=user.ledger_id, pattern=core, category_id=changes["category_id"]))
        tx.needs_review = False
        for other in (
            db.query(models.Transaction)
            .filter(models.Transaction.ledger_id == user.ledger_id, models.Transaction.merchant.contains(core),
                    models.Transaction.needs_review.is_(True), models.Transaction.id != tx.id,
                    models.Transaction.tx_type == tx.tx_type, models.Transaction.refund_of_id.is_(None))
            .all()
        ):
            other.category_id = changes["category_id"]
            other.needs_review = False

    db.commit()
    db.refresh(tx)
    return tx


@router.delete("/{tx_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_transaction(
    tx_id: int,
    db: Session = Depends(get_db),
    user: models.User = Depends(get_current_user),
):
    tx = (
        db.query(models.Transaction)
        .filter(models.Transaction.id == tx_id, models.Transaction.ledger_id == user.ledger_id)
        .first()
    )
    if not tx:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "transaction not found")
    db.delete(tx)
    db.commit()


@router.get("/summary/monthly", response_model=schemas.MonthSummary)
def monthly_summary(
    year: int,
    month: int,
    db: Session = Depends(get_db),
    user: models.User = Depends(get_current_user),
):
    """Pay-cycle summary. Transfers are ignored and a returned purchase stops counting as spending, see app/stats.py."""
    start_day = user.ledger.cycle_start_day or 1
    p_start, p_end = cycle_bounds(start_day, year, month)
    data = period_data(db, user.ledger_id, p_start, p_end)
    cats = {c.id: c.name for c in db.query(models.Category).filter(models.Category.ledger_id == user.ledger_id)}
    buckets: dict[tuple, Decimal] = {}
    for t, eff in data.expenses:
        k = (t.category_id, models.TxType.expense)
        buckets[k] = buckets.get(k, Decimal(0)) + eff
    for r in data.standalone_refunds:
        k = (r.category_id, models.TxType.expense)
        buckets[k] = buckets.get(k, Decimal(0)) - r.amount
    for t in data.incomes:
        k = (t.category_id, models.TxType.income)
        buckets[k] = buckets.get(k, Decimal(0)) + t.amount
    by_category = [
        schemas.SummaryByCategory(category_id=cid, category_name=cats.get(cid, "未分类"), tx_type=kind, total=total)
        for (cid, kind), total in buckets.items() if total != 0
    ]
    return schemas.MonthSummary(
        year=year, month=month, total_income=data.income, total_expense=data.expense, by_category=by_category,
        period_start=p_start.strftime("%Y-%m-%d"), period_end=(p_end - timedelta(days=1)).strftime("%Y-%m-%d"),
    )
