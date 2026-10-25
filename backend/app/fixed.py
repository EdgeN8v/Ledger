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


def matches(item: dict, amount, merchant: Optional[str], text: str, when: Optional[datetime], sub: Optional[str] = None) -> bool:
    # "match_sub": a payment the user (or a keyword) filed under one of these sub-categories IS this item, whatever
    # shop name or amount it came with - for bills whose company name and amount change every month
    if sub and sub in (item.get("match_sub") or []):
        return True
    amounts = item.get("match_amount") or []
    merchants = item.get("match_merchants") or []
    texts = item.get("match_text") or []
    cores_exact = item.get("match_core") or []             # the bare shop name must BE one of these ...
    cores_part = item.get("match_core_part") or []         # ... or contain one of these (a named person)
    if not (amounts or merchants or texts or cores_exact or cores_part):
        return False
    if cores_exact or cores_part:
        from app.merchants import shop_core
        core = shop_core(merchant)
        if not (core in cores_exact or any(p in core for p in cores_part)):
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


def item_for(items: list[dict], amount, merchant, text, when, sub: Optional[str] = None) -> Optional[dict]:
    for it in items:
        if matches(it, amount, merchant, text, when, sub):
            return it
    return None


def cycle_starts_between(start_day: int, after: date, upto: date) -> int:
    """How many pay-cycle starts (the `start_day` of a month) fall after `after` and on or before `upto`."""
    n, y, m = 0, after.year, after.month
    while True:
        d = date(y, m, min(start_day, calendar.monthrange(y, m)[1]))
        if d > upto:
            return n
        if d > after:
            n += 1
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)


def pot_amount(item: dict, start_day: int, last_paid: Optional[date], today: date) -> Decimal:
    """The sinking fund of a lumpy bill (rent every 3 months, parking 2-3 months at once): the reserve the plan sets aside
    every cycle, added up since the bill was last paid, never more than the bill itself ("pot_cap"). Paid on 9-27 with
    4900 a cycle: 4900 after the 20 Oct, 9800 after the 20 Nov, 14700 after the 20 Dec - exactly the next bill."""
    cap = Decimal(str(item.get("pot_cap") or 0))
    per = Decimal(str(item.get("monthly") or 0))
    if cap <= 0 or per <= 0 or last_paid is None:
        return ZERO
    return min(cap, per * cycle_starts_between(start_day, last_paid, today))


def cycle_key(year: int, month: int) -> str:
    """A pay cycle is named after the month it starts in (the 20th): cycle '2026-09' runs 20 Sep - 19 Oct."""
    return f"{year}-{month:02d}"


def marked_paid(item: dict, key: str, today: Optional[date] = None) -> Optional[str]:
    """Why this item counts as paid for the cycle without a matching payment on record: the user marked it, or it was
    paid in advance up to a date. Returns a short label, or None."""
    until = item.get("paid_until")
    if until and today is not None and today.isoformat() <= until:
        return "已付到" + f"{int(until[5:7])}月{int(until[8:10])}日"
    if key in (item.get("paid_cycles") or []):
        return "本期已付(手动标记)"
    return None


def reminders(items: list[dict], today: date, paid_this_month, key: Optional[str] = None) -> list[dict]:
    """Bills to pay soon. An item opts in with  "remind": {"before_days": 5, "months": [3, 6, 9, 12], "amount": 14700}:
    it shows from before_days before the END of every listed month (all months when "months" is absent) until it is paid.
    paid_this_month(item) -> bool tells whether a matching payment already happened this calendar month."""
    out = []
    for it in items:
        r = it.get("remind")
        if not isinstance(r, dict):
            continue
        before = int(r.get("before_days", 5))
        if key and key in (it.get("paid_cycles") or []):
            continue
        until = it.get("paid_until")
        if until:                                  # paid in advance: it falls due on that date, not at the month end
            last = date.fromisoformat(until)
            left = (last - today).days
            if left < 0 or left > before:
                continue
        else:
            months = r.get("months")
            if months and today.month not in months:
                continue
            last = date(today.year, today.month, calendar.monthrange(today.year, today.month)[1])
            left = (last - today).days
            if left > before:
                continue
            if paid_this_month(it):
                continue
        amount = r.get("amount") or it.get("monthly") or 0
        out.append({"name": it["name"], "amount": Decimal(str(amount)), "due_date": last.isoformat(), "days_left": left, "note": it.get("note")})
    return out
