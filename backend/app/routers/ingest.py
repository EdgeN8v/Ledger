"""Phones forward every payment notification / bank SMS here as raw text. The server is the single
place that parses it, so parser fixes take effect without shipping a new app build."""
import hashlib
import re
from datetime import timedelta
from typing import Optional

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app import models, schemas
from app.clock import from_epoch_ms
from app.database import get_db
from app.deps import get_current_user
from app.parsers import Parsed, clean_text, looks_financial, parse_message
from app.fixed import category_name_for, load
from app.refunds import link_refund, relink_orphans
from app.flights import MILEAGE, parse_flights, store_flights, sync_flight_trips

router = APIRouter(prefix="/ingest", tags=["ingest"])

MERGE_WINDOW = timedelta(minutes=10)
SOURCE_MAP = {
    "com.tencent.mm": "wechat",
    "com.eg.android.AlipayGphone": "alipay",
    "com.android.mms": "sms",
    "com.google.android.apps.messaging": "sms",
    "com.miui.mms": "sms",
    "sms": "sms", "wechat": "wechat", "alipay": "alipay",
}


def _fingerprint(source: str, title: str, text: str, posted_minute: str) -> str:
    return hashlib.sha1(f"{source}|{title}|{text}|{posted_minute}".encode("utf-8")).hexdigest()


def _pick_category(db: Session, ledger_id: int, tx_type: str, merchant: Optional[str], text: str, amount=None, when=None):
    """Returns (category, guessed). Learned merchant rules win; then the per-category keyword lists; the last
    resort is a guess (flagged so the user can long-press to change it)."""
    hay = f"{merchant or ''} {text}"
    if amount is not None:
        ledger = db.get(models.Ledger, ledger_id)
        fixed_cat = category_name_for(load(ledger), tx_type, amount, merchant, text, when) if ledger else None
        if fixed_cat:
            cat = (db.query(models.Category).filter(models.Category.ledger_id == ledger_id, models.Category.name == fixed_cat).first())
            if cat:
                return cat, False
    for rule in db.query(models.MerchantRule).filter(models.MerchantRule.ledger_id == ledger_id):
        if rule.pattern and rule.pattern.lower() in hay.lower():
            cat = db.get(models.Category, rule.category_id)
            if cat:
                return cat, False
    cats = (
        db.query(models.Category)
        .filter(models.Category.ledger_id == ledger_id, models.Category.tx_type == models.TxType(tx_type))
        .all()
    )
    # the most specific (longest) matching keyword wins, so "京东健康" beats "京东"
    best, best_len = None, 0
    for cat in cats:
        for kw in (cat.keywords or "").split(","):
            kw = kw.strip()
            if kw and kw in hay and len(kw) > best_len:
                best, best_len = cat, len(kw)
    if best is None:
        return _fallback_category(cats, tx_type, merchant), True
    return best, False


_NAME_ONLY = re.compile(r"^(?:支付宝外部商户-|支付宝-|微信支付-|商户_)?[一-鿿]{2,4}$")


def _fallback_category(cats, tx_type: str, merchant: Optional[str]):
    """Nothing matched: a small payment to an individual is almost always a food stall / small vendor; anything
    else lands in the catch-all so no record is left uncategorised (the user can long-press to change it)."""
    by_name = {c.name: c for c in cats}
    if tx_type == "expense" and merchant and _NAME_ONLY.match(merchant.strip()):
        return by_name.get("餐饮")
    return by_name.get("其他支出" if tx_type == "expense" else "其他收入")


def _find_merge_target(db: Session, ledger_id: int, parsed: Parsed, when) -> Optional[models.Transaction]:
    """The same purchase is often reported twice (WeChat notification + bank SMS). Merge when the amount
    and direction match within a short window AND the new report comes from a source the record has not
    seen yet - two coffees bought back to back come from the same source and stay two records."""
    candidates = (
        db.query(models.Transaction)
        .filter(
            models.Transaction.ledger_id == ledger_id,
            models.Transaction.tx_type == models.TxType(parsed.tx_type),
            models.Transaction.amount == parsed.amount,
            models.Transaction.occurred_at >= when - MERGE_WINDOW,
            models.Transaction.occurred_at <= when + MERGE_WINDOW,
        )
        .order_by(models.Transaction.occurred_at.desc())
        .all()
    )
    for tx in candidates:
        seen = set((tx.sources or "").split(","))
        if tx.source != models.TxSource.manual and parsed.origin not in seen and tx.sources:
            return tx
    return None


def _is_own_name(db: Session, ledger_id: int, merchant: Optional[str]) -> bool:
    """Money to/from the account holder's own name is a move between own accounts, not income or spending."""
    if not merchant:
        return False
    names = (db.get(models.Ledger, ledger_id).owner_names or "").split(",")
    return any(n.strip() and n.strip() == merchant.strip() for n in names)


def _update_balance(db: Session, ledger_id: int, parsed: Parsed, when) -> None:
    """Bank SMS state the balance after the transaction: keep the account's balance current."""
    if parsed.balance is None or not parsed.account_hint:
        return
    acct = (
        db.query(models.Account)
        .filter(models.Account.ledger_id == ledger_id, models.Account.name == parsed.account_hint)
        .first()
    )
    if acct is None:
        acct = models.Account(ledger_id=ledger_id, name=parsed.account_hint, kind="bank")
        db.add(acct)
    if acct.balance_at is None or when >= acct.balance_at:
        acct.balance = parsed.balance
        acct.balance_at = when


def _pair_transfer(db: Session, ledger_id: int, tx: models.Transaction) -> None:
    """Two bank accounts reporting the same amount in opposite directions within minutes is almost always
    the user moving money between their own cards. Mark both as a transfer so neither shows up as spending
    or income; the user can flip the flag if it was really two unrelated payments."""
    if tx.channel != "bank" or tx.refund_of_id or tx.is_transfer or not tx.account_hint:
        return
    opposite = models.TxType.income if tx.tx_type == models.TxType.expense else models.TxType.expense
    other = (
        db.query(models.Transaction)
        .filter(
            models.Transaction.ledger_id == ledger_id,
            models.Transaction.channel == "bank",
            models.Transaction.tx_type == opposite,
            models.Transaction.amount == tx.amount,
            models.Transaction.account_hint != tx.account_hint,
            models.Transaction.is_transfer.is_(False),
            models.Transaction.refund_of_id.is_(None),
            models.Transaction.occurred_at >= tx.occurred_at - timedelta(minutes=10),
            models.Transaction.occurred_at <= tx.occurred_at + timedelta(minutes=10),
        )
        .first()
    )
    if other:
        tx.is_transfer = other.is_transfer = True
        tx.needs_review = other.needs_review = False


@router.post("", response_model=schemas.IngestResult)
def ingest(
    payload: schemas.IngestRequest,
    db: Session = Depends(get_db),
    user: models.User = Depends(get_current_user),
):
    source = SOURCE_MAP.get(payload.source)
    if source is None:
        return schemas.IngestResult(status="ignored")

    posted = from_epoch_ms(payload.posted_at_ms)
    # the SMS app prefixes a running "[39条]" counter that changes with every message: not part of the content
    title, text = payload.title or "", clean_text(payload.text or "")
    fp = _fingerprint(source, title, text, posted.strftime("%Y%m%d%H%M"))

    existing = (
        db.query(models.RawEvent)
        .filter(models.RawEvent.ledger_id == user.ledger_id, models.RawEvent.fingerprint == fp)
        .first()
    )
    if existing:
        tx = db.get(models.Transaction, existing.transaction_id) if existing.transaction_id else None
        return schemas.IngestResult(status="duplicate", event_id=existing.id, transaction=tx)

    ticket = parse_flights(text, posted)
    mileage = bool(MILEAGE.search(text))
    parsed = None if (ticket or mileage) else parse_message(source, title, text, posted)
    status, tx = "ignored", None

    if ticket:
        # an airline ticket SMS describes a flight (and so a business trip); it is never money by itself
        store_flights(db, user.ledger_id, ticket)
        status = "flight"
    elif mileage:
        status = "ignored"
    elif parsed is None:
        status = "failed" if looks_financial(f"{title} {text}") else "ignored"
    else:
        when = parsed.occurred_at or posted
        same = (
            db.query(models.Transaction)
            .filter(
                models.Transaction.ledger_id == user.ledger_id,
                models.Transaction.amount == parsed.amount,
                models.Transaction.occurred_at >= when - MERGE_WINDOW,
                models.Transaction.occurred_at <= when + MERGE_WINDOW,
                models.Transaction.raw_text.contains(text),
            )
            .first()
        )
        target = None if same else _find_merge_target(db, user.ledger_id, parsed, when)
        if same:
            # the exact same message re-posted by the phone (notification update): never a second record
            tx, status = same, "duplicate"
        elif target:
            seen = set(filter(None, (target.sources or "").split(",")))
            seen.add(parsed.origin)
            target.sources = ",".join(sorted(seen))
            # the bank SMS carries the exact time and merchant text; wallets carry the clean merchant name
            if parsed.occurred_at and parsed.channel == "bank":
                target.occurred_at = parsed.occurred_at
            if parsed.merchant and not target.merchant:
                target.merchant = parsed.merchant
            if parsed.account_hint and not target.account_hint:
                target.account_hint = parsed.account_hint
            target.raw_text = f"{target.raw_text or ''}\n---\n{text}".strip()
            tx, status = target, "merged"
        else:
            cat, guessed = _pick_category(db, user.ledger_id, parsed.tx_type, parsed.merchant, text, parsed.amount, when)
            tx = models.Transaction(
                ledger_id=user.ledger_id,
                user_id=user.id,
                category_id=cat.id if cat else None,
                tx_type=models.TxType(parsed.tx_type),
                amount=parsed.amount,
                merchant=parsed.merchant,
                note=parsed.merchant,
                channel=parsed.channel,
                account_hint=parsed.account_hint,
                sources=parsed.origin,
                source=models.TxSource.sms if source == "sms" else models.TxSource.notification,
                raw_text=text,
                needs_review=False if (parsed.is_transfer or _is_own_name(db, user.ledger_id, parsed.merchant)) else ((guessed or not parsed.merchant) if not parsed.is_refund else True),
                is_transfer=parsed.is_transfer or _is_own_name(db, user.ledger_id, parsed.merchant),
                is_refund=parsed.is_refund,
                occurred_at=when,
            )
            db.add(tx)
            status = "created"
        if status in ("created", "merged"):
            _update_balance(db, user.ledger_id, parsed, when)
        db.flush()
        if status == "created":
            _pair_transfer(db, user.ledger_id, tx)
            if parsed.is_refund:
                link_refund(db, tx)        # a refund cancels the purchase it belongs to
            db.flush()
            relink_orphans(db, user.ledger_id)   # ...and a purchase may complete an earlier-arrived refund

    ev = models.RawEvent(
        ledger_id=user.ledger_id, user_id=user.id, fingerprint=fp, source=source,
        title=title, text=text, posted_at=posted, status=status,
        transaction_id=tx.id if tx else None,
    )
    db.add(ev)
    db.commit()
    if status == "flight":
        sync_flight_trips(db, user.ledger_id)
    if tx:
        db.refresh(tx)
    return schemas.IngestResult(status=status, event_id=ev.id, transaction=tx)


@router.get("/failed", response_model=list[schemas.RawEventOut])
def failed_events(
    limit: int = 50,
    db: Session = Depends(get_db),
    user: models.User = Depends(get_current_user),
):
    """Looked like a payment but matched no template - the samples new parser rules are written from."""
    return (
        db.query(models.RawEvent)
        .filter(models.RawEvent.ledger_id == user.ledger_id, models.RawEvent.status == "failed")
        .order_by(models.RawEvent.id.desc())
        .limit(limit)
        .all()
    )
