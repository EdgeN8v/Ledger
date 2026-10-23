"""Phones forward every payment notification / bank SMS here as raw text. The server is the single
place that parses it, so parser fixes take effect without shipping a new app build."""
import hashlib
import re
from datetime import timedelta
from decimal import Decimal
from typing import Optional

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException
from sqlalchemy.orm import Session

from app import models, schemas, screenpay
from app.clock import from_epoch_ms
from app.database import SessionLocal, get_db
from app.deps import get_current_user
from app.parsers import Parsed, clean_text, looks_financial, parse_message
from app.fixed import category_name_for, load
from app.refunds import link_refund, relink_orphans
from app.maintenance import WALLET_ONLY_NOTE, sweep_wallet_only
from app.merchants import UNNAMED_DEDUCTION, is_unnamed_deduction, rule_applies
from app.flights import MILEAGE, looks_like_ticket, note_return_ticket, parse_flights, store_flights, sync_flight_trips

router = APIRouter(prefix="/ingest", tags=["ingest"])

MERGE_WINDOW = timedelta(minutes=10)
_FAMILY_CARD = re.compile(r"亲情卡消费")              # Alipay: "M***Y（**怡）使用你赠送的亲情卡消费X元" - someone else's spending, not the user's own
SOURCE_MAP = {
    "com.tencent.mm": "wechat",
    "com.eg.android.AlipayGphone": "alipay",
    "com.android.mms": "sms",
    "com.google.android.apps.messaging": "sms",
    "com.miui.mms": "sms",
    "sms": "sms", "wechat": "wechat", "alipay": "alipay",
}


# Shopping apps: their order notices only say WHAT was bought. For now they are just collected (status "hint"), to see
# whether they can be matched to payments; nothing in the ledger is created or changed from them.
SHOP_APPS = {"com.jingdong.app.mall": "jd", "com.xunmeng.pinduoduo": "pdd", "com.taobao.taobao": "taobao", "com.tmall.wireless": "tmall", "com.jd.jrapp": "jdfinance"}
_ORDER_NOTICE = re.compile(r"已发货|已签收|已出库|待收货|已揽收|派送|取件|包裹|订单|已下单|已支付|退款|退货|售后|换货")
_PROMO = re.compile(r"优惠|红包|秒杀|领券|降价|会员日|猜你喜欢|直播|签到|福利|限时|抢购|折扣|满\d+减|活动|立减|补贴|大促|爆款")
# a reward plug like "点击领取2元流量红包" / "立即领取2积分" rides along on almost every Alipay/WeChat notice; its
# wording (红包/积分/优惠券, and whatever random word sits before it - "流量", "停车"...) has nothing to do with
# what was bought, but can still land a false keyword hit (e.g. "流量" -> 通讯), so it never enters category matching
_REWARD_CTA = re.compile(r"[，,]?\s*(?:点击|立即)?领取[^\n，,。.！!]{0,12}(?:红包|积分|优惠券)[！!。.]?")


def _store_order_hint(db: Session, user, payload) -> "schemas.IngestResult":
    title, text = payload.title or "", clean_text(payload.text or "")
    if not _ORDER_NOTICE.search(f"{title} {text}") or _PROMO.search(f"{title} {text}"):
        return schemas.IngestResult(status="ignored")
    posted = from_epoch_ms(payload.posted_at_ms)
    src = "shop:" + SHOP_APPS[payload.source]
    fp = _fingerprint(src, title, text, posted.strftime("%Y%m%d%H%M"))
    if db.query(models.RawEvent).filter(models.RawEvent.ledger_id == user.ledger_id, models.RawEvent.fingerprint == fp).first():
        return schemas.IngestResult(status="duplicate")
    ev = models.RawEvent(ledger_id=user.ledger_id, user_id=user.id, fingerprint=fp, source=src, title=title[:200], text=text[:600],
                         posted_at=posted, status="hint")
    db.add(ev)
    db.commit()
    return schemas.IngestResult(status="hint", event_id=ev.id)


def _fingerprint(source: str, title: str, text: str, posted_minute: str) -> str:
    return hashlib.sha1(f"{source}|{title}|{text}|{posted_minute}".encode("utf-8")).hexdigest()


def _pick_category(db: Session, ledger_id: int, tx_type: str, merchant: Optional[str], text: str, amount=None, when=None):
    """Returns (category, guessed). Learned merchant rules win; then the per-category keyword lists; the last
    resort is a guess (flagged so the user can long-press to change it)."""
    hay = _REWARD_CTA.sub("", f"{merchant or ''} {text}")
    if amount is not None:
        ledger = db.get(models.Ledger, ledger_id)
        fixed_cat = category_name_for(load(ledger), tx_type, amount, merchant, text, when) if ledger else None
        if fixed_cat:
            cat = (db.query(models.Category).filter(models.Category.ledger_id == ledger_id, models.Category.name == fixed_cat).first())
            if cat:
                return cat, False
    rules = sorted(db.query(models.MerchantRule).filter(models.MerchantRule.ledger_id == ledger_id), key=lambda r: -len(r.pattern or ""))
    if is_unnamed_deduction(merchant, text):
        rule = next((r for r in rules if r.pattern == UNNAMED_DEDUCTION), None)
        cat = db.get(models.Category, rule.category_id) if rule else None
        if cat:
            return cat, False
    for rule in rules:                                    # the most specific shop name first
        if rule_applies(rule.pattern, merchant):
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


def _confident_salary(db: Session, ledger_id: int, cat: Optional[models.Category], text: str, amount) -> bool:
    """A bank deposit with no payer name (every payday SMS looks like this - the bank never names the employer)
    can skip the usual review when its memo says "工资" and the amount is in line with recent real salaries."""
    if not cat or cat.name != "工资" or "工资" not in text:
        return False
    recent = (
        db.query(models.Transaction.amount)
        .filter(models.Transaction.ledger_id == ledger_id, models.Transaction.category_id == cat.id,
                models.Transaction.tx_type == models.TxType.income, models.Transaction.raw_text.contains("工资"))
        .order_by(models.Transaction.occurred_at.desc())
        .limit(3)
        .all()
    )
    amounts = [r[0] for r in recent]
    if not amounts:
        return False
    return min(amounts) * Decimal("0.5") <= amount <= max(amounts) * Decimal("1.5")


_NAME_ONLY = re.compile(r"^(?:支付宝外部商户-|支付宝-|微信支付-|商户_)?[一-鿿]{2,4}$")


def _fallback_category(cats, tx_type: str, merchant: Optional[str]):
    """Nothing matched: a small payment to an individual is almost always a food stall / small vendor; anything
    else lands in the catch-all so no record is left uncategorised (the user can long-press to change it)."""
    by_name = {c.name: c for c in cats}
    from app.merchants import shop_core
    if tx_type == "expense" and shop_core(merchant) in ("", "消费", "微信支付", "支付宝-消费", "微信", "支付宝"):
        return by_name.get("其他支出")                      # a payment that names nothing is not "probably a meal"
    if tx_type == "expense" and merchant and _NAME_ONLY.match(merchant.strip()):
        return by_name.get("餐饮")
    return by_name.get("其他支出" if tx_type == "expense" else "其他收入")


def _find_merge_target(db: Session, ledger_id: int, parsed: Parsed, when, text: str = "") -> Optional[models.Transaction]:
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
        # Alipay announces a family-card payment twice ("...使用你赠送的亲情卡消费" and "你有一笔..元的支出"): one payment
        # (the bank SMS may already have joined the record: it is still the same payment)
        if parsed.origin == "alipay" and "alipay" in seen and ("亲情卡" in (tx.raw_text or "")) != ("亲情卡" in text):
            return tx
    return None


def _is_own_name(db: Session, ledger_id: int, merchant: Optional[str]) -> bool:
    """Money to/from the account holder's own name is a move between own accounts, not income or spending."""
    if not merchant:
        return False
    ledger = db.get(models.Ledger, ledger_id)
    names = [n.strip() for n in f"{ledger.owner_names or ''},{getattr(ledger, 'family_names', None) or ''}".split(",") if n.strip()]
    m = merchant.strip()
    tail = m.split("-")[-1].strip()                           # "微信转账-兔兔" -> "兔兔"
    return any(n == m or (n == tail and m.startswith(("微信转账", "转账"))) for n in names)


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


@router.put("/pending-category", response_model=schemas.IngestResult)
def set_pending_category(
    payload: schemas.PendingCategoryIn,
    db: Session = Depends(get_db),
    user: models.User = Depends(get_current_user),
):
    """The payment-page popup got a category tap before the bank SMS created the transaction: remember the
    choice (screenpay.apply_pending applies it once the SMS lands)."""
    if not screenpay.set_pending_category(db, user.ledger_id, payload.event_id, payload.category_id, payload.sub):
        raise HTTPException(404, "no matching pending event")
    return schemas.IngestResult(status="pending", event_id=payload.event_id)


def _relink_later(ledger_id: int) -> None:
    """relink_orphans scans every refund of the ledger (about 3 s on the real data): run it after the response
    has gone out, so the phone's category popup does not wait for it."""
    db = SessionLocal()
    try:
        relink_orphans(db, ledger_id)
        db.commit()
    finally:
        db.close()


@router.post("", response_model=schemas.IngestResult)
def ingest(
    payload: schemas.IngestRequest,
    background: BackgroundTasks,
    db: Session = Depends(get_db),
    user: models.User = Depends(get_current_user),
):
    if payload.source in SHOP_APPS:
        return _store_order_hint(db, user, payload)
    if payload.source in screenpay.SOURCES:
        lines = [l for l in (payload.text or "").splitlines()][:30]
        status, tx, event_id, amount, payee = screenpay.store_and_apply(db, user.ledger_id, user.id, from_epoch_ms(payload.posted_at_ms), lines, payload.source)
        sug_cat, sug_sub = (screenpay.suggest_category(db, user.ledger_id, f"{screenpay.SOURCES[payload.source]}-{payee}")
                            if payee else (None, None))
        return schemas.IngestResult(status=status, event_id=event_id, transaction=tx, amount=amount, payee=payee,
                                    suggested_category_id=sug_cat, suggested_sub=sug_sub)
    source = SOURCE_MAP.get(payload.source)
    if source is None:
        return schemas.IngestResult(status="ignored")

    posted = from_epoch_ms(payload.posted_at_ms)
    # the SMS app prefixes a running "[39条]" counter that changes with every message: not part of the content
    title, text = payload.title or "", clean_text(payload.text or "")
    fp = _fingerprint(source, title, text, posted.strftime("%Y%m%d%H%M"))
    if payload.device and payload.notif_key:
        # the same text twice can be two real payments (same shop, same amount, minutes apart): only the same
        # notification (same phone, same Android key) is a repeat
        fp = _fingerprint(source, title, f"{text}|{payload.device}|{payload.notif_key}", posted.strftime("%Y%m%d%H%M"))

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
    ctrip = (not ticket) and note_return_ticket(db, user.ledger_id, text, posted)
    parsed = None if (ticket or mileage or ctrip) else parse_message(source, title, text, posted)
    zero = parsed is not None and parsed.amount is not None and parsed.amount <= 0      # "已支付 ¥0.00" is not a payment
    if zero:
        parsed = None
    status, tx = "ignored", None

    if ctrip:
        status = "flight"
    elif ticket:
        # an airline ticket SMS describes a flight (and so a business trip); it is never money by itself
        store_flights(db, user.ledger_id, ticket)
        status = "flight"
    elif mileage:
        status = "ignored"
    elif parsed is None and zero:
        status = "ignored"
    elif parsed is None:
        status = "failed" if (looks_financial(f"{title} {text}") or looks_like_ticket(text)) else "ignored"
    else:
        when = parsed.occurred_at or posted
        same = _same_notice(db, user.ledger_id, payload) if (payload.device and payload.notif_key) else (
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
        echo = None
        # (the 亲情卡 notice is left out: it carries "this was the family card" and must merge as before)
        if not same and source in ("wechat", "alipay") and parsed.tx_type == "expense" and not _FAMILY_CARD.search(text):
            # two phones signed in to the same ledger (and the same Alipay/WeChat) each get their own copy of the
            # payment notice, worded slightly differently ("点击领取2个支付宝积分" vs "点此查看详情"): the second copy
            # is the same payment, never a second purchase. A real second purchase of the same amount still gets
            # its own record from its bank SMS.
            echo = (_other_phones_copy(db, user.ledger_id, source, payload.device, parsed.amount, posted) if payload.device else
                    db.query(models.Transaction)
                    .filter(models.Transaction.ledger_id == user.ledger_id, models.Transaction.amount == parsed.amount,
                            models.Transaction.tx_type == models.TxType.expense, models.Transaction.sources.contains(parsed.origin),
                            models.Transaction.occurred_at >= when - timedelta(minutes=2), models.Transaction.occurred_at <= when + timedelta(minutes=2))
                    .first())
        if echo:
            same = echo
        target = None if same else _find_merge_target(db, user.ledger_id, parsed, when, text)
        twin = None
        if not same and not target and not (parsed.merchant or "").strip() and parsed.tx_type == "expense":
            # a second, shop-less notice of a payment the same app already reported with its shop name
            twin = (db.query(models.Transaction)
                    .filter(models.Transaction.ledger_id == user.ledger_id, models.Transaction.amount == parsed.amount,
                            models.Transaction.tx_type == models.TxType.expense, models.Transaction.merchant.isnot(None), models.Transaction.merchant != "",
                            models.Transaction.sources.contains(parsed.origin),
                            models.Transaction.occurred_at >= when - timedelta(minutes=3), models.Transaction.occurred_at <= when + timedelta(minutes=3))
                    .first())
        if twin:
            tx, status = twin, "duplicate"
        elif same:
            # the exact same message re-posted by the phone (notification update): never a second record
            tx, status = same, "duplicate"
        elif target:
            was_unnamed = not (target.merchant or "").strip()
            if target.note == WALLET_ONLY_NOTE:                  # the bank SMS did come, only late: it counts after all
                target.is_transfer, target.note = False, None
            seen = set(filter(None, (target.sources or "").split(",")))
            seen.add(parsed.origin)
            target.sources = ",".join(sorted(seen))
            # the bank SMS carries the exact time and merchant text; wallets carry the clean merchant name
            if parsed.occurred_at and parsed.channel == "bank":
                target.occurred_at = parsed.occurred_at
            if parsed.merchant and (not target.merchant or (target.merchant.endswith("微信转账") and parsed.merchant.startswith("微信转账-"))):
                target.merchant = parsed.merchant                # the notification names the person the bank SMS leaves out
                if _is_own_name(db, user.ledger_id, parsed.merchant):
                    target.is_transfer, target.needs_review = True, False
            if parsed.account_hint and not target.account_hint:
                target.account_hint = parsed.account_hint
            target.raw_text = f"{target.raw_text or ''}\n---\n{text}".strip()
            if _FAMILY_CARD.search(text):
                target.family_card = True                    # Alipay says someone else spent from the shared balance
            if (target.needs_review or was_unnamed) and target.merchant and not target.category_locked and not target.refund_of_id:
                # the first report (a wallet notification) often has no shop name; now it has one: look again.
                # skip this for a refund already paired with its purchase (refund.category_id above) - its own
                # text usually says "退款", which would otherwise win the keyword match and undo the inheritance
                cat, guessed = _pick_category(db, user.ledger_id, target.tx_type.value, target.merchant, target.raw_text, target.amount, target.occurred_at)
                if cat and not guessed:
                    target.category_id, target.needs_review = cat.id, False
            tx, status = target, "merged"
        else:
            cat, guessed = _pick_category(db, user.ledger_id, parsed.tx_type, parsed.merchant, text, parsed.amount, when)
            confident_salary = not parsed.merchant and not parsed.is_refund and _confident_salary(db, user.ledger_id, cat, text, parsed.amount)
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
                needs_review=False if (parsed.is_transfer or _is_own_name(db, user.ledger_id, parsed.merchant) or confident_salary) else ((guessed or (not parsed.merchant and not (is_unnamed_deduction(parsed.merchant, text) and not guessed))) if not parsed.is_refund else True),
                is_transfer=parsed.is_transfer or _is_own_name(db, user.ledger_id, parsed.merchant),
                is_refund=parsed.is_refund,
                family_card=bool(_FAMILY_CARD.search(text)),
                occurred_at=when,
            )
            db.add(tx)
            status = "created"
        if status in ("created", "merged"):
            _update_balance(db, user.ledger_id, parsed, when)
        db.flush()
        if status in ("created", "merged"):
            screenpay.apply_pending(db, tx)           # the shop name may already have been read off the payment page
        if status == "created":
            _pair_transfer(db, user.ledger_id, tx)
            if parsed.is_refund:
                link_refund(db, tx)        # a refund cancels the purchase it belongs to
            db.flush()
            background.add_task(_relink_later, user.ledger_id)   # ...and a purchase may complete an earlier-arrived refund

    # what is not a payment is nobody's business (chat messages, codes, ads): keep only the fingerprint that stops repeats
    keep = status != "ignored"
    ev = models.RawEvent(
        ledger_id=user.ledger_id, user_id=user.id, fingerprint=fp, source=source,
        title=title if keep else "", text=text if keep else "", posted_at=posted, status=status,
        transaction_id=tx.id if tx else None, device=payload.device, notif_key=payload.notif_key,
    )
    db.add(ev)
    db.commit()
    sweep_wallet_only(db, user.ledger_id)
    if status == "flight":
        sync_flight_trips(db, user.ledger_id)
    if tx:
        db.refresh(tx)
    return schemas.IngestResult(status=status, event_id=ev.id, transaction=tx, **_popup_fields(db, source, status, tx))


def _same_notice(db: Session, ledger_id: int, payload):
    """The transaction this very notification (same phone, same Android key) already went into, if any: a
    notification updated or re-read from the shade, never a new payment."""
    E = models.RawEvent
    ev = (db.query(E).filter(E.ledger_id == ledger_id, E.device == payload.device, E.notif_key == payload.notif_key,
                             E.transaction_id.isnot(None)).first())
    return db.get(models.Transaction, ev.transaction_id) if ev else None


def _other_phones_copy(db: Session, ledger_id: int, source: str, device: str, amount, posted):
    """A payment another phone of this ledger already reported from the same wallet, within 2 minutes, that this
    phone has not reported yet: then this notice is that phone's copy, not a second purchase. A second notice of
    the same amount from the SAME phone is always a new payment."""
    E, T = models.RawEvent, models.Transaction
    candidates = (db.query(T).join(E, E.transaction_id == T.id)
                  .filter(E.ledger_id == ledger_id, E.source == source, E.device.isnot(None), E.device != device,
                          E.posted_at >= posted - timedelta(minutes=2), E.posted_at <= posted + timedelta(minutes=2),
                          T.amount == amount, T.tx_type == models.TxType.expense)
                  .order_by(E.posted_at.desc()).all())
    for tx in candidates:
        mine = db.query(E).filter(E.transaction_id == tx.id, E.source == source, E.device == device).first()
        if not mine:
            return tx
    return None


def _popup_fields(db: Session, source: str, status: str, tx) -> dict:
    """A WeChat/Alipay payment notice ("交易提醒: 你有一笔4.70元的支出") is usually the first sign of a payment -
    the payment page itself often cannot be read (Taobao's 免密支付 page shows only "免密支付中"). So like the
    screen:* path, hand the phone what its category popup needs. Only for a real purchase seen for the first
    time: not a re-post, an income, a refund or a transfer between own accounts."""
    if source not in ("wechat", "alipay") or status not in ("created", "merged") or tx is None:
        return {}
    if tx.tx_type != models.TxType.expense or tx.is_transfer or tx.is_refund or tx.refund_of_id:
        return {}
    if tx.category_id and not tx.needs_review:
        cat, sub = tx.category_id, tx.sub          # already settled (a shop rule, or a choice made on the page popup)
    else:
        cat, sub = screenpay.suggest_category(db, tx.ledger_id, tx.merchant) if tx.merchant else (None, None)
    return {"amount": tx.amount, "payee": tx.merchant or "", "suggested_category_id": cat, "suggested_sub": sub}


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
