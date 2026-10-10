"""Bring in a bank statement the SMS never covered. The caller turns the statement into plain rows; this module files
them, skipping what the ledger already knows (the same row imported twice, or the same payment already seen by SMS)."""
import hashlib
from datetime import datetime, timedelta
from decimal import Decimal
from typing import Optional

from sqlalchemy.orm import Session

from app import models
from app.clock import now_local


def import_rows(db: Session, ledger_id: int, user_id: int, rows: list[dict], tag: str) -> dict:
    """Each row: key (unique inside the statement), when, amount, tx_type ('expense'|'income'), merchant, note, account,
    category (name, or omitted to file it like a live payment: learned rules, then keywords, then a flagged guess), is_transfer,
    is_refund. Returns counts: added / duplicate / seen / transfers_paired."""
    cats = {c.name: c.id for c in db.query(models.Category).filter(models.Category.ledger_id == ledger_id)}
    from app.refunds import link_refund
    from app.routers.ingest import _pick_category
    out = {"added": 0, "duplicate": 0, "seen": 0, "transfers_paired": 0}
    for r in rows:
        fp = hashlib.sha1(f"import|{tag}|{r['key']}".encode("utf-8")).hexdigest()
        if db.query(models.RawEvent).filter(models.RawEvent.ledger_id == ledger_id, models.RawEvent.fingerprint == fp).first():
            out["duplicate"] += 1
            continue
        amount = Decimal(str(r["amount"]))
        when: datetime = r["when"]
        # an SMS (or a notification) already recorded this payment: do not count it twice
        if not r.get("split"):
            near = (db.query(models.Transaction)
                    .filter(models.Transaction.ledger_id == ledger_id, models.Transaction.tx_type == models.TxType(r["tx_type"]),
                            models.Transaction.amount == amount, models.Transaction.account_hint == r.get("account"),
                            models.Transaction.sources != f"import:{tag}",       # two equal payments inside one statement are two payments
                            models.Transaction.occurred_at >= when - timedelta(minutes=5), models.Transaction.occurred_at <= when + timedelta(minutes=5))
                    .first())
            if near:
                out["seen"] += 1
                db.add(models.RawEvent(ledger_id=ledger_id, user_id=user_id, fingerprint=fp, source="import", title=tag,
                                       text="", posted_at=when, status="duplicate", transaction_id=near.id))
                continue
        cat_id, review = cats.get(r.get("category")), False
        if not r.get("category") and not r.get("is_transfer"):
            cat, guessed = _pick_category(db, ledger_id, r["tx_type"], r.get("merchant"), r.get("note") or "", amount, when)
            cat_id, review = (cat.id if cat else None), bool(guessed)
        tx = models.Transaction(
            ledger_id=ledger_id, user_id=user_id, category_id=cat_id, tx_type=models.TxType(r["tx_type"]),
            amount=amount, note=r.get("note"), merchant=r.get("merchant"), channel="bank", account_hint=r.get("account"),
            sources=f"import:{tag}", source=models.TxSource.manual, raw_text=f"[导入 {tag}] {r.get('note') or ''}".strip(),
            needs_review=review, is_transfer=bool(r.get("is_transfer")), is_refund=bool(r.get("is_refund")),
            category_locked=bool(r.get("category")), occurred_at=when,
        )
        db.add(tx)
        db.flush()
        if r.get("is_refund"):
            link_refund(db, tx)                      # a refund cancels the purchase it belongs to
        if r.get("is_transfer") and r["tx_type"] == "expense":
            # the other half (the receiving account's SMS) is the same move of money: neither is income
            other = (db.query(models.Transaction)
                     .filter(models.Transaction.ledger_id == ledger_id, models.Transaction.tx_type == models.TxType.income,
                             models.Transaction.amount == amount, models.Transaction.is_transfer.is_(False),
                             models.Transaction.occurred_at >= when - timedelta(minutes=15), models.Transaction.occurred_at <= when + timedelta(minutes=15))
                     .first())
            if other:
                other.is_transfer = True
                out["transfers_paired"] += 1
        db.add(models.RawEvent(ledger_id=ledger_id, user_id=user_id, fingerprint=fp, source="import", title=tag, text="",
                               posted_at=when, status="created", transaction_id=tx.id))
        out["added"] += 1
    db.commit()
    return out
