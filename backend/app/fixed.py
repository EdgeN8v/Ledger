"""The user's fixed commitments (rent, parents' allowance, parking, utilities ...).

Each item is a small JSON object stored on the ledger:
  name, monthly (amount reserved every pay cycle; 0 = learn it from the last cycles), tracked (does the payment show up
  in the SMS at all? rent paid from the card without SMS is not tracked), category (where matching payments are filed),
  match_amount [..], match_merchants [..], match_text [..], due_day + tolerance_days (usual payday of the month).

Matching payments are filed in the item's category when they arrive, and the savings plan sets the item's reserve aside
before working out what is left to spend freely, so a lumpy bill (rent every 3 months, parking 2-3 months at once) does
not wreck the daily allowance.
"""
import calendar
import json
from datetime import date, datetime
from decimal import Decimal
from typing import Optional

ZERO = Decimal(0)


def load(ledger) -> list[dict]:
    try:
        items = json.loads(ledger.fixed_items or "[]")
    except ValueError:
        return []
    return [i for i in items if isinstance(i, dict) and i.get("name")]


def _day_ok(item: dict, when: Optional[datetime]) -> bool:
    day, tol = item.get("due_day"), item.get("tolerance_days", 3)
    if not day or when is None:
        return True
    return abs(when.day - int(day)) <= int(tol) or abs(when.day - int(day)) >= 31 - int(tol)


def matches(item: dict, amount, merchant: Optional[str], text: str, when: Optional[datetime]) -> bool:
    amounts = item.get("match_amount") or []
    merchants = item.get("match_merchants") or []
    texts = item.get("match_text") or []
    if not (amounts or merchants or texts):
        return False
    if amounts and not any(abs(float(amount) - float(a)) < 0.005 for a in amounts):
        return False
    hay = f"{merchant or ''} {text or ''}"
    if merchants and not any(m in (merchant or "") for m in merchants):
        return False
    if texts and not any(t in hay for t in texts):
        return False
    return _day_ok(item, when)


def category_name_for(items: list[dict], tx_type: str, amount, merchant, text, when) -> Optional[str]:
    if tx_type != "expense":
        return None
    for it in items:
        if it.get("category") and matches(it, amount, merchant, text, when):
            return it["category"]
    return None


def item_for(items: list[dict], amount, merchant, text, when) -> Optional[dict]:
    for it in items:
        if matches(it, amount, merchant, text, when):
            return it
    return None


def reminders(items: list[dict], today: date, paid_this_month) -> list[dict]:
    """Bills to pay soon. An item opts in with  "remind": {"before_days": 5, "months": [3, 6, 9, 12], "amount": 14700}:
    it shows from before_days before the END of every listed month (all months when "months" is absent) until it is paid.
    paid_this_month(item) -> bool tells whether a matching payment already happened this calendar month."""
    out = []
    for it in items:
        r = it.get("remind")
        if not isinstance(r, dict):
            continue
        months = r.get("months")
        if months and today.month not in months:
            continue
        last = date(today.year, today.month, calendar.monthrange(today.year, today.month)[1])
        left = (last - today).days
        if left > int(r.get("before_days", 5)):
            continue
        if paid_this_month(it):
            continue
        amount = r.get("amount") or it.get("monthly") or 0
        out.append({"name": it["name"], "amount": Decimal(str(amount)), "due_date": last.isoformat(), "days_left": left, "note": it.get("note")})
    return out
