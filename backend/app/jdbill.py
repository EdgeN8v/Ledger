"""JD (京东) trade statements: the bank SMS only says "京东支付-京东商城", the statement says WHAT was bought.

The user exports "交易流水" in the JD Finance app (a password-protected zip with a CSV). Each paid order is matched to the
payment already in the ledger by amount and time, and the product title is filed under it as the note; the kind of thing
(car parts, food, clothes, gifts...) then follows from the title and JD's own category.
Nothing is created or deleted here: payments that cannot be matched are only reported.
"""
import csv
import io
import re
import zipfile
from datetime import datetime, timedelta
from decimal import Decimal
from typing import Optional

from sqlalchemy.orm import Session

from app import models, subcats

_AMOUNT = re.compile(r"^\s*([0-9]+(?:\.[0-9]+)?)")
_JD_SHOP = re.compile(r"京东|网银在线|JD", re.I)
_LATER = re.compile(r"白条|小金库|京东金融|网银")          # pay-later / wallet orders reach the bank weeks later, as one JD payment
_LATER_PAY = ("京东白条", "京东小金库", "余额")
_GIFT = re.compile(r"礼盒|送礼|礼品")
_DELIVERY = re.compile(r"人份|套餐|单人餐")
_DRUG = re.compile(r"酊|膏|药|胶囊|颗粒|口服液|贴")


def read_zip(data: bytes, password: str) -> str:
    z = zipfile.ZipFile(io.BytesIO(data))
    names = [n for n in z.namelist() if n.lower().endswith(".csv")] or z.namelist()
    raw = z.read(names[0], pwd=password.encode())
    for enc in ("utf-8-sig", "gb18030"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    raise ValueError("cannot decode the statement")


def parse(text: str) -> list[dict]:
    lines = text.splitlines()
    start = next((i for i, l in enumerate(lines) if l.startswith("交易时间,")), None)
    if start is None:
        raise ValueError("not a JD trade statement")
    out = []
    for r in csv.reader(lines[start + 1:]):
        if len(r) < 9:
            continue
        when, shop, title, amount, pay, status, io_kind, kind, order = (x.strip() for x in r[:9])
        m = _AMOUNT.match(amount)
        if not m or status != "交易成功":
            continue
        refunded_all = "已全额退款" in amount
        if io_kind != "支出" and not (io_kind == "不计收支" and refunded_all):
            continue                                   # income (interest), repayments, refund rows
        amt = Decimal(m.group(1))
        if amt <= 0:
            continue
        try:
            at = datetime.strptime(when, "%Y-%m-%d %H:%M:%S")
        except ValueError:
            continue
        out.append({"at": at, "shop": shop, "title": title, "amount": amt, "pay": pay, "kind": kind, "order": order})
    return out


def _target(row: dict) -> tuple[Optional[str], Optional[str]]:
    """(category name, sub) from the title and JD's category; None = leave the category as it is."""
    title, kinds = row["title"], row["kind"].split()
    if row["shop"] == "京东外卖" or _DELIVERY.search(title):
        return "餐饮", "外卖"
    if "亲密卡" in title:
        return "家人", None
    if _GIFT.search(title):
        return "人情", None
    if len(kinds) != 1:
        kinds = []
    k = kinds[0] if kinds else ""
    if k == "汽车用品":
        return "我的车", None
    if k == "食品酒饮":
        food = subcats.food_instead_of_shopping(title, None)
        return "餐饮", food or "零食"
    if k in ("美妆个护", "清洁纸品", "日用百货", "家居家装"):
        return "购物", "日用品"
    if k in ("数码电器", "电脑办公"):
        return "购物", "电子产品"
    if k == "运动户外":
        return "购物", "服装"
    if k == "医疗保健":
        return ("医疗", None) if _DRUG.search(title) else ("购物", "日用品")
    if k == "生活服务" and "话费" in title:
        return "通讯", None
    return None, None


def analyze(db: Session, ledger_id: int, rows: list[dict]) -> tuple[list[tuple[dict, models.Transaction]], list[dict]]:
    T = models.Transaction
    used: set[int] = set()
    matched, missing = [], []
    for row in sorted(rows, key=lambda r: r["at"]):
        day0 = row["at"].replace(hour=0, minute=0, second=0, microsecond=0)
        cands = (db.query(T).filter(T.ledger_id == ledger_id, T.tx_type == models.TxType.expense, T.is_transfer.is_(False),
                                    T.refund_of_id.is_(None), T.amount == row["amount"],
                                    T.occurred_at >= day0 - timedelta(days=1), T.occurred_at < day0 + timedelta(days=2)).all())
        cands = [c for c in cands if c.id not in used]
        if not cands and row["pay"] in _LATER_PAY:
            late = (db.query(T).filter(T.ledger_id == ledger_id, T.tx_type == models.TxType.expense, T.is_transfer.is_(False),
                                       T.refund_of_id.is_(None), T.amount == row["amount"],
                                       T.occurred_at >= day0, T.occurred_at < day0 + timedelta(days=75)).all())
            cands = [c for c in late if c.id not in used and _LATER.search(c.merchant or "")]
        if not cands:
            missing.append(row)
            continue

        def score(c: models.Transaction):
            shop_hit = 0 if _JD_SHOP.search(c.merchant or "") else 1
            return (shop_hit, abs((c.occurred_at - row["at"]).total_seconds()))
        best = min(cands, key=score)
        used.add(best.id)
        matched.append((row, best))
    return matched, missing


def apply(db: Session, ledger_id: int, matched: list[tuple[dict, models.Transaction]]) -> dict:
    cats = {c.name: c for c in db.query(models.Category).filter(models.Category.ledger_id == ledger_id)}
    stats = {"noted": 0, "recategorized": 0, "subbed": 0}
    for row, tx in matched:
        title = row["title"][:160]
        if not tx.note or tx.note == tx.merchant or "京东" in (tx.note or ""):
            tx.note = title
            stats["noted"] += 1
        cat_name, sub = _target(row)
        locked = bool(tx.category_locked)
        if cat_name and not locked and cat_name in cats and tx.category_id != cats[cat_name].id:
            tx.category_id = cats[cat_name].id
            stats["recategorized"] += 1
        cur = db.get(models.Category, tx.category_id)
        name = cur.name if cur else None
        if name in subcats.SUBS:
            new = sub if (sub in subcats.SUBS[name] and not locked) else subcats.guess(name, tx.merchant, tx.note)
            if new and new != tx.sub:
                tx.sub = new
                stats["subbed"] += 1
        elif tx.sub is not None:
            tx.sub = None
    db.commit()
    return stats


def summary(matched, missing, stats: Optional[dict] = None) -> dict:
    return {
        "orders": len(matched) + len(missing), "matched": len(matched), "unmatched": len(missing),
        "unmatched_list": [{"date": r["at"].strftime("%Y-%m-%d"), "amount": str(r["amount"]), "title": r["title"][:40], "pay": r["pay"]} for r in missing],
        **(stats or {}),
    }
