"""Pairing refunds with the purchases they cancel.

A returned item must stop counting as spending. The refund is stored as its own income-looking record linked to
the purchase (refund_of_id), and the summaries net them off - so a purchase refunded in full disappears from the
totals, and a partial refund only removes the refunded part. Pairing works in both directions because phones can
deliver the refund notification before the payment's.
"""
import re
from datetime import timedelta
from decimal import Decimal
from typing import Optional

from sqlalchemy import func
from sqlalchemy.orm import Session

from app import models

WINDOW = timedelta(days=120)
_GENERIC = {"支付宝", "支付宝外部商户", "微信支付", "财付通", "京东支付", "京东商城", "天猫", "淘宝", "抖音支付",
            "京东商城业务", "拼多多平台商户", "易宝支付", "云闪付"}


def _segments(merchant: Optional[str]) -> list[str]:
    return [s.strip("*… ") for s in re.split(r"[-/]", merchant or "") if s.strip("*… ") and s.strip("*… ") not in _GENERIC]


def same_shop(a: Optional[str], b: Optional[str]) -> bool:
    """Loose match between a refund's merchant text and a purchase's ('韶音（SHOKZ）京东*' vs '韶音（SHOKZ）京东自营旗舰店')."""
    for x in _segments(a):
        for y in _segments(b):
            if len(x) >= 3 and len(y) >= 3 and (x in y or y in x or x[:4] == y[:4]):
                return True
    return False


def refunded_amount(db: Session, expense_id: int) -> Decimal:
    total = (
        db.query(func.coalesce(func.sum(models.Transaction.amount), 0))
        .filter(models.Transaction.refund_of_id == expense_id)
        .scalar()
    )
    return Decimal(total or 0)


def link_refund(db: Session, refund: models.Transaction, refunded: Optional[dict] = None) -> Optional[models.Transaction]:
    """Attach this refund to the purchase it cancels (if one can be identified) and return that purchase."""
    if refund.refund_of_id or refund.tx_type != models.TxType.income:
        return None
    candidates = (
        db.query(models.Transaction)
        .filter(
            models.Transaction.ledger_id == refund.ledger_id,
            models.Transaction.tx_type == models.TxType.expense,
            models.Transaction.is_transfer.is_(False),
            models.Transaction.refund_of_id.is_(None),
            models.Transaction.amount >= refund.amount,
            models.Transaction.occurred_at <= refund.occurred_at,
            models.Transaction.occurred_at >= refund.occurred_at - WINDOW,
        )
        .order_by(models.Transaction.occurred_at.desc())
        .all()
    )
    room = [(e, e.amount - (refunded.get(e.id, 0) if refunded is not None else refunded_amount(db, e.id))) for e in candidates]
    room = [(e, left) for e, left in room if left >= refund.amount]
    exact = [e for e, left in room if e.amount == refund.amount and left == e.amount]   # untouched, same amount
    target = None
    for pool in (
        [e for e in exact if same_shop(refund.merchant, e.merchant)],
        [e for e in exact if timedelta(0) <= refund.occurred_at - e.occurred_at <= timedelta(hours=12)],   # a payee-less refund ("财付通"): the same amount paid hours ago
        exact if len(exact) == 1 else [],
        [e for e, _ in room if same_shop(refund.merchant, e.merchant)],     # partial refund: shop must match
    ):
        if pool:
            target = pool[0]
            break
    if target is None:
        return None
    refund.refund_of_id = target.id
    refund.category_id = target.category_id
    refund.needs_review = False
    if refunded is not None:
        refunded[target.id] = refunded.get(target.id, 0) + refund.amount
    return target


def relink_orphans(db: Session, ledger_id: Optional[int] = None) -> int:
    """Try to pair every refund that is still unpaired. Cheap: unpaired refunds are few."""
    q = db.query(models.Transaction).filter(
        models.Transaction.tx_type == models.TxType.income,
        models.Transaction.refund_of_id.is_(None),
        models.Transaction.is_transfer.is_(False),
        models.Transaction.raw_text.contains("退"),
    )
    if ledger_id is not None:
        q = q.filter(models.Transaction.ledger_id == ledger_id)
    from app.stats import refunded_map
    refunded = {}
    for lid in {r.ledger_id for r in q.all()}:               # one pass for the money already refunded, not one query per candidate
        refunded.update(refunded_map(db, lid))
    n = 0
    for r in q.order_by(models.Transaction.occurred_at).all():
        if link_refund(db, r, refunded):
            n += 1
    return n
