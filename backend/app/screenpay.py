"""The shop name of a WeChat payment, read from the payment-success screen on the phone.

The bank SMS and the WeChat notification of a scan-to-pay payment name no shop ("扫二维码付款"); the success page of WeChat
does. The phone's accessibility reader (only active inside WeChat, only on a page that says 支付成功) sends the few short
lines of that page; this module finds the amount and the payee in them and puts the payee on the matching payment.
Everything about reading the lines lives here, so a change in WeChat's layout is fixed on the server.
"""
import re
from datetime import timedelta
from decimal import Decimal
from typing import Optional

from sqlalchemy.orm import Session

from app import models
from app.merchants import is_generic, shop_core

_AMOUNT = re.compile(r"^[¥￥]?\s*([0-9][0-9,]*\.[0-9]{2})$")
_SKIP = {"支付成功", "付款成功", "微信支付", "完成", "返回", "详情", "账单详情", "查看账单", "支付方式", "优惠", "收款方", "商品", "交易对象", "零钱", "零钱通", "关闭", "确定"}
WINDOW = timedelta(minutes=20)
SOURCES = {"screen:wechat": "微信支付", "screen:alipay": "支付宝"}     # which app the page was read in -> how its payments are named


def parse(lines: list[str]) -> tuple[Optional[Decimal], Optional[str]]:
    """(amount, payee) from the short text lines of the success page; payee = the nearest meaningful line around the amount."""
    clean = [l.strip() for l in lines if l and l.strip()]
    idx = next((i for i, l in enumerate(clean) if _AMOUNT.match(l)), None)
    if idx is None:
        return None, None
    amount = Decimal(_AMOUNT.match(clean[idx]).group(1).replace(",", ""))

    def ok(l: str) -> bool:
        return l not in _SKIP and not _AMOUNT.match(l) and not re.fullmatch(r"[0-9\-:. ]+", l) and "¥" not in l and "￥" not in l and len(l) >= 2

    before = [l for l in reversed(clean[:idx]) if ok(l)]
    after = [l for l in clean[idx + 1:] if ok(l)]
    payee = (before or after or [None])[0]
    return amount, payee


def _generic(tx: models.Transaction) -> bool:
    core = shop_core(tx.merchant)
    return not core or is_generic(core) or "扫二维码付款" in core


def apply_to(db: Session, tx: models.Transaction, payee: str, prefix: str = "微信支付") -> bool:
    """Name a payment that has no shop name after the page's payee, and look at its category again."""
    if not _generic(tx):
        return False
    from app.routers.ingest import _pick_category            # lazy: ingest imports this module
    tx.merchant = f"{prefix}-{payee}"
    tx.raw_text = f"{tx.raw_text or ''}\n---\n收款方:{payee}".strip()
    if not tx.category_locked:
        cat, guessed = _pick_category(db, tx.ledger_id, tx.tx_type.value, tx.merchant, tx.raw_text, tx.amount, tx.occurred_at)
        if cat and not guessed:
            tx.category_id, tx.needs_review, tx.sub = cat.id, False, None
    return True


def store_and_apply(db: Session, ledger_id: int, user_id: int, posted, lines: list[str], source: str = "screen:wechat") -> str:
    amount, payee = parse(lines)
    if amount is None or amount <= 0 or not payee:
        return "ignored"
    from app.routers.ingest import _fingerprint
    fp = _fingerprint(source, payee, f"{amount:.2f}", posted.strftime("%Y%m%d%H%M"))
    if db.query(models.RawEvent).filter(models.RawEvent.ledger_id == ledger_id, models.RawEvent.fingerprint == fp).first():
        return "duplicate"
    T = models.Transaction
    tx = (db.query(T).filter(T.ledger_id == ledger_id, T.tx_type == models.TxType.expense, T.amount == amount,
                             T.occurred_at >= posted - WINDOW, T.occurred_at <= posted + WINDOW)
          .order_by(T.occurred_at.desc()).all())
    tx = next((t for t in tx if _generic(t)), None)
    applied = bool(tx) and apply_to(db, tx, payee, SOURCES[source])
    db.add(models.RawEvent(ledger_id=ledger_id, user_id=user_id, fingerprint=fp, source=source, title=payee, text=f"{amount:.2f}", posted_at=posted,
                           status="applied" if applied else "hint", transaction_id=tx.id if applied else None))
    db.commit()
    return "applied" if applied else "hint"


def apply_pending(db: Session, tx: models.Transaction) -> None:
    """A payment that arrived after the page was read: use the waiting page hint with the same amount."""
    if tx.tx_type != models.TxType.expense or not _generic(tx):
        return
    E = models.RawEvent
    ev = (db.query(E).filter(E.ledger_id == tx.ledger_id, E.source.in_(list(SOURCES)), E.status == "hint", E.text == f"{Decimal(tx.amount):.2f}",
                             E.posted_at >= tx.occurred_at - WINDOW, E.posted_at <= tx.occurred_at + WINDOW)
          .order_by(E.posted_at.desc()).first())
    if ev and ev.title and apply_to(db, tx, ev.title, SOURCES[ev.source]):
        ev.status, ev.transaction_id = "applied", tx.id
