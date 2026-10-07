"""Startup upkeep that keeps existing ledgers in step with code changes."""
from sqlalchemy.orm import Session

from app import models
from app.default_categories import DEFAULT_CATEGORIES
from app.parsers import parse_message


def ensure_columns(db: Session) -> None:
    """create_all adds tables but never columns: add the ones introduced later (additive only)."""
    from sqlalchemy import inspect, text
    wanted = {"trips": [("auto_key", "VARCHAR"), ("dates_locked", "BOOLEAN DEFAULT FALSE NOT NULL"), ("open_end", "BOOLEAN DEFAULT FALSE NOT NULL")],
              "ledgers": [("home_city", "VARCHAR")]}
    insp = inspect(db.get_bind())
    for table, cols in wanted.items():
        have = {c["name"] for c in insp.get_columns(table)}
        for name, ddl in cols:
            if name not in have:
                db.execute(text(f"ALTER TABLE {table} ADD COLUMN {name} {ddl}"))
    db.commit()


def ignore_mileage_notes(db: Session) -> None:
    """After-flight mileage notes were stored as 'failed' payments: they are not payments."""
    from app.flights import MILEAGE
    for ev in db.query(models.RawEvent).filter(models.RawEvent.status == "failed"):
        if MILEAGE.search(ev.text or ""):
            ev.status = "ignored"
    db.commit()


def sync_default_categories(db: Session) -> None:
    """Existing ledgers got their category list at registration. Add categories that were introduced
    later and merge new default keywords into existing ones (a user's own extra keywords are kept)."""
    for ledger in db.query(models.Ledger).all():
        existing = {c.name: c for c in db.query(models.Category).filter(models.Category.ledger_id == ledger.id)}
        for name, icon, tx_type, keywords in DEFAULT_CATEGORIES:
            cat = existing.get(name)
            if cat is None:
                db.add(models.Category(ledger_id=ledger.id, name=name, icon=icon, tx_type=tx_type, keywords=keywords))
                continue
            have = [k.strip() for k in (cat.keywords or "").split(",") if k.strip()]
            extra = [k for k in keywords.split(",") if k and k not in have]
            if extra:
                cat.keywords = ",".join(have + extra)
    db.commit()


def backfill_balances(db: Session) -> None:
    """Rebuild account balances from stored bank SMS (oldest first), so balances exist for messages that
    arrived before accounts were a feature. Never overwrites a newer balance."""
    events = (
        db.query(models.RawEvent)
        .filter(models.RawEvent.source == "sms", models.RawEvent.status.in_(("created", "merged")))
        .order_by(models.RawEvent.posted_at)
        .all()
    )
    for ev in events:
        parsed = parse_message("sms", ev.title or "", ev.text or "", ev.posted_at)
        if not parsed or parsed.balance is None or not parsed.account_hint:
            continue
        when = parsed.occurred_at or ev.posted_at
        acct = (
            db.query(models.Account)
            .filter(models.Account.ledger_id == ev.ledger_id, models.Account.name == parsed.account_hint)
            .first()
        )
        if acct is None:
            acct = models.Account(ledger_id=ev.ledger_id, name=parsed.account_hint, kind="bank")
            db.add(acct)
            db.flush()
        if acct.balance_at is None or when >= acct.balance_at:
            acct.balance, acct.balance_at = parsed.balance, when
    db.commit()


def relink_orphan_refunds(db: Session) -> None:
    """Pair refunds with purchases (including partial and split refunds) whenever the server starts."""
    from app.refunds import relink_orphans
    relink_orphans(db)
    db.commit()
def sync_refund_categories(db: Session) -> None:
    """A refund always belongs to the category of the purchase it cancels (keeps category totals netting out)."""
    for r in db.query(models.Transaction).filter(models.Transaction.refund_of_id.isnot(None)).all():
        orig = db.get(models.Transaction, r.refund_of_id)
        if orig and orig.category_id and r.category_id != orig.category_id:
            r.category_id = orig.category_id
    db.commit()


def recategorize_uncategorized(db: Session) -> None:
    """Records the user has not confirmed (no category yet, or flagged for review) get a fresh pick whenever
    the keyword lists or learned rules improve. A record the user edited has needs_review=False and is never
    touched."""
    from app.routers.ingest import _pick_category
    rows = (
        db.query(models.Transaction)
        .filter(models.Transaction.is_transfer.is_(False), models.Transaction.refund_of_id.is_(None),
                (models.Transaction.category_id.is_(None)) | (models.Transaction.needs_review.is_(True)))
        .all()
    )
    for t in rows:
        cat, guessed = _pick_category(db, t.ledger_id, t.tx_type.value, t.merchant, t.raw_text or "", t.amount, t.occurred_at)
        if cat:
            t.category_id = cat.id
            t.needs_review = bool(guessed or not t.merchant)
    db.commit()


def flag_existing_refunds(db: Session) -> None:
    """Older rows predate the is_refund column: a refund is an income record paired with a purchase or whose
    message says 退货/退款 (a 退库 tax refund is real income and is left alone)."""
    import re
    pat = re.compile(r"退货|退款|已退")
    for t in db.query(models.Transaction).filter(models.Transaction.tx_type == models.TxType.income,
                                                 models.Transaction.is_refund.is_(False)).all():
        if t.refund_of_id or (t.raw_text and pat.search(t.raw_text)):
            t.is_refund = True
    db.commit()


def merge_car_and_transport(db: Session) -> None:
    """Everything on the road is one category now (the user drives to work, trains and planes are rare): move
    '交通' into '车辆', rename it, and remove the emptied category."""
    for ledger in db.query(models.Ledger).all():
        by_name = {c.name: c for c in db.query(models.Category).filter(models.Category.ledger_id == ledger.id)}
        car, trans = by_name.get("车辆") or by_name.get("车辆出行"), by_name.get("交通")
        if car is None:
            continue
        if trans is not None and trans.id != car.id:
            have = [k.strip() for k in (car.keywords or "").split(",") if k.strip()]
            car.keywords = ",".join(have + [k.strip() for k in (trans.keywords or "").split(",") if k.strip() and k.strip() not in have])
            db.query(models.Transaction).filter(models.Transaction.category_id == trans.id).update({"category_id": car.id})
            db.query(models.MerchantRule).filter(models.MerchantRule.category_id == trans.id).update({"category_id": car.id})
            db.flush()
            db.delete(trans)
        car.name = "车辆出行"
        car.icon = "🚗"
    db.commit()


def apply_fixed_items(db: Session) -> None:
    """The user's fixed commitments are facts they stated (mum's allowance, the rent), so payments matching them are
    filed under the item's category even if an earlier guess put them elsewhere."""
    from app.fixed import category_name_for, load
    for ledger in db.query(models.Ledger).all():
        items = load(ledger)
        if not items:
            continue
        cats = {c.name: c.id for c in db.query(models.Category).filter(models.Category.ledger_id == ledger.id)}
        for t in db.query(models.Transaction).filter(models.Transaction.ledger_id == ledger.id,
                                                    models.Transaction.tx_type == models.TxType.expense,
                                                    models.Transaction.is_transfer.is_(False),
                                                    models.Transaction.refund_of_id.is_(None)).all():
            name = category_name_for(items, "expense", t.amount, t.merchant, t.raw_text or "", t.occurred_at)
            if name and name in cats and t.category_id != cats[name]:
                t.category_id = cats[name]
                t.needs_review = False
    db.commit()


def normalise_merchant_rules(db: Session) -> None:
    """Rules learned before the channel prefix was stripped ("微信支付-xx") are rewritten to the bare shop name."""
    from app.merchants import shop_core
    seen = {}
    for r in db.query(models.MerchantRule).order_by(models.MerchantRule.id).all():
        core = shop_core(r.pattern)
        if len(core) < 2 or core == r.pattern:
            continue
        key = (r.ledger_id, core)
        clash = seen.get(key) or db.query(models.MerchantRule).filter(
            models.MerchantRule.ledger_id == r.ledger_id, models.MerchantRule.pattern == core).first()
        if clash is not None and clash.id != r.id:
            db.delete(r)
        else:
            r.pattern = core
            seen[key] = r
    db.commit()


def fix_keyword_conflicts(db: Session) -> None:
    """Keywords that were moved between categories must also move in ledgers that already have the old list:
    a travel agency ("携程") is travel, not entertainment."""
    for ledger in db.query(models.Ledger).all():
        ent = db.query(models.Category).filter(models.Category.ledger_id == ledger.id, models.Category.name == "娱乐").first()
        if ent and ent.keywords:
            ent.keywords = ",".join(k for k in ent.keywords.split(",") if k.strip() and k.strip() != "携程")
    # the same word in two categories is decided by list order; keep each shop in exactly one place
    for ledger in db.query(models.Ledger).all():
        shop = db.query(models.Category).filter(models.Category.ledger_id == ledger.id, models.Category.name == "购物").first()
        if shop and shop.keywords:
            shop.keywords = ",".join(k for k in shop.keywords.split(",") if k.strip() and k.strip() != "盒马")
    db.commit()
