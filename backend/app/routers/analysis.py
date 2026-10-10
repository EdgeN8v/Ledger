"""Where did the money go: per-cycle breakdown with comparison to the previous cycle, and the year in review."""
import re
from collections import defaultdict
from datetime import date, datetime, timedelta
from decimal import Decimal
from typing import Optional

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app import models, schemas
from app.database import get_db
from app.deps import get_current_user
from app.stats import current_cycle, cycle_bounds, period_data

router = APIRouter(prefix="/analysis", tags=["analysis"])
ZERO = Decimal(0)
_PREFIX = re.compile(r"^(?:支付宝外部商户|支付宝|微信支付|财付通|京东支付|抖音支付|易宝支付)[-/]")
SMALL = Decimal(30)


def _shop(merchant: Optional[str]) -> str:
    m = (merchant or "未知商户").strip()
    prev = None
    while prev != m:                       # "支付宝-淘宝-托树云" -> "托树云"
        prev, m = m, _PREFIX.sub("", m)
    m = re.sub(r"^(?:天猫|淘宝|京东商城)[-/]", "", m)
    return m.strip("*… ") or "未知商户"


def _prev_cycle(year: int, month: int) -> tuple[int, int]:
    return (year - 1, 12) if month == 1 else (year, month - 1)


def _by_category(data, cats):
    by = defaultdict(lambda: [ZERO, 0])
    for _t, amount in data.reimbursements:
        by[-2][0] -= amount
    for t, eff in data.expenses:
        cid = -2 if t.trip_id in data.business_ids else t.category_id
        by[cid][0] += eff
        by[cid][1] += 1
    for r in data.standalone_refunds:
        by[r.category_id][0] -= r.amount
    return {cid: v for cid, v in by.items() if v[0] != 0}


@router.get("/cycle", response_model=schemas.CycleAnalysis)
def analyse_cycle(
    year: Optional[int] = None,
    month: Optional[int] = None,
    include_business: bool = False,
    db: Session = Depends(get_db),
    user: models.User = Depends(get_current_user),
):
    return cycle_analysis(db, user.ledger, year, month, include_business)


def cycle_analysis(db: Session, l: models.Ledger, year: Optional[int] = None, month: Optional[int] = None,
                   include_business: bool = False) -> schemas.CycleAnalysis:
    sd = l.cycle_start_day or 1
    if not year or not month:
        year, month = current_cycle(sd)
    start, end = cycle_bounds(sd, year, month)
    data = period_data(db, l.id, start, end, include_business)
    py, pm = _prev_cycle(year, month)
    pstart, pend = cycle_bounds(sd, py, pm)
    prev = period_data(db, l.id, pstart, pend, include_business)
    cats = {c.id: c for c in db.query(models.Category).filter(models.Category.ledger_id == l.id)}

    cur_by, prev_by = _by_category(data, cats), _by_category(prev, cats)
    total = data.expense if data.expense > 0 else Decimal(1)
    categories = []
    for cid, (amt, n) in sorted(cur_by.items(), key=lambda kv: kv[1][0], reverse=True):
        c = cats.get(cid)
        before = prev_by.get(cid, [ZERO, 0])[0]
        categories.append(schemas.CategoryStat(
            category_id=cid, name=c.name if c else ("出差垫付" if cid == -2 else "未分类"), icon=c.icon if c else ("🧾" if cid == -2 else "❓"),
            total=amt, count=n, share=float(amt / total), previous_total=before,
            change_pct=float((amt - before) / before * 100) if before > 0 else None,
        ))

    shops = defaultdict(lambda: [ZERO, 0])
    for t, eff in data.expenses:
        s = shops[_shop(t.merchant)]
        s[0] += eff
        s[1] += 1
    top_shops = [
        schemas.ShopStat(name=n, total=v[0], count=v[1])
        for n, v in sorted(shops.items(), key=lambda kv: kv[1][0], reverse=True)[:8]
    ]
    biggest = [
        schemas.BigSpend(id=t.id, category_id=t.category_id, merchant=_shop(t.merchant), amount=eff, date=t.occurred_at.strftime("%Y-%m-%d"),
                         category=cats[t.category_id].name if t.category_id in cats else "未分类")
        for t, eff in sorted(data.expenses, key=lambda x: x[1], reverse=True)[:5]
    ]

    days = (end - start).days
    daily_map = defaultdict(lambda: ZERO)
    weekday = defaultdict(lambda: ZERO)
    for t, eff in data.expenses:
        daily_map[t.occurred_at.date()] += eff
        weekday[t.occurred_at.weekday()] += eff
    daily = []
    for i in range(days):
        d = (start + timedelta(days=i)).date()
        if d > date.today():
            break
        daily.append(schemas.DayStat(date=d.strftime("%Y-%m-%d"), total=daily_map.get(d, ZERO)))
    elapsed = max(len(daily), 1)

    weekly = []
    cursor = start.date()
    while cursor < end.date() and cursor <= date.today():
        week_end = min(cursor + timedelta(days=6 - cursor.weekday()), (end - timedelta(days=1)).date())
        total_w = sum((daily_map.get(cursor + timedelta(days=i), ZERO) for i in range((week_end - cursor).days + 1)), ZERO)
        weekly.append(schemas.WeekStat(start=cursor.strftime("%Y-%m-%d"), end=week_end.strftime("%Y-%m-%d"), total=total_w))
        cursor = week_end + timedelta(days=1)

    small = [(t, eff) for t, eff in data.expenses if eff <= SMALL]
    small_total = sum((e for _, e in small), ZERO)
    insights = _insights(data, prev, categories, top_shops, biggest, small, small_total, elapsed)
    trip_rows = defaultdict(lambda: ZERO)
    for t, eff in data.expenses:
        if t.trip_id:
            trip_rows[t.trip_id] += eff
    names = {t.id: t.name for t in db.query(models.Trip).filter(models.Trip.ledger_id == l.id)}
    trips = [schemas.TripBrief(id=i, name=names.get(i, "出行"), total=v) for i, v in sorted(trip_rows.items(), key=lambda kv: kv[1], reverse=True)]
    trip_total = sum(trip_rows.values(), ZERO)
    for tb in trips[:2]:
        insights.insert(1, f"✈️ {tb.name}共花 ¥{tb.total:,.0f} (油费、过路费、吃饭等), 属于一次性出行, 不算你日常的花法")

    return schemas.CycleAnalysis(
        year=year, month=month, period_start=start.strftime("%Y-%m-%d"), period_end=(end - timedelta(days=1)).strftime("%Y-%m-%d"),
        total_expense=data.expense, total_income=data.income, previous_expense=prev.expense,
        avg_daily=(data.expense / elapsed).quantize(Decimal("0.01")), categories=categories, top_shops=top_shops,
        biggest=biggest, daily=daily, weekday_totals=[weekday.get(i, ZERO) for i in range(7)], weekly=weekly, trips=trips, trip_total=trip_total,
        small_count=len(small), small_total=small_total, insights=insights,
        family_total=sum((eff for t, eff in data.expenses if t.family_card), ZERO),
    )


def _insights(data, prev, categories, shops, biggest, small, small_total, elapsed) -> list[str]:
    out = []
    if data.expense > 0 and prev.expense > 0:
        diff = (data.expense - prev.expense) / prev.expense * 100
        out.append(f"本账期已花 ¥{data.expense:,.0f}, 上一账期整期 ¥{prev.expense:,.0f}"
                   + (f", 目前已是上期的 {data.expense / prev.expense * 100:.0f}%" if diff < 100 else ", 已超过上一整期"))
    if categories:
        top = categories[0]
        out.append(f"{top.icon}{top.name}最多: ¥{top.total:,.0f}, 占 {top.share * 100:.0f}% ({top.count} 笔)")
        for c in categories[1:]:
            if c.change_pct is not None and c.change_pct >= 50 and c.total >= 200:
                out.append(f"{c.icon}{c.name}比上期涨了 {c.change_pct:.0f}% (¥{c.previous_total:,.0f} → ¥{c.total:,.0f}), 值得留意")
                break
    if small and small_total > 0:
        share = small_total / data.expense * 100 if data.expense > 0 else 0
        out.append(f"{len(small)} 笔 ≤30元的小额消费共 ¥{small_total:,.0f} (占 {share:.0f}%), 单笔不多但最容易不知不觉花掉")
    if biggest and data.expense > 0 and biggest[0].amount / data.expense > Decimal("0.2"):
        b = biggest[0]
        out.append(f"最大一笔 {b.merchant} ¥{b.amount:,.0f}, 一笔占了本期支出的 {b.amount / data.expense * 100:.0f}%")
    if shops and shops[0].count >= 4:
        s = shops[0]
        out.append(f"去「{s.name}」{s.count} 次共 ¥{s.total:,.0f}, 是你本期最常光顾的地方")
    if not out:
        out.append("这个账期还没有足够的消费记录可以分析")
    return out


@router.get("/year", response_model=schemas.YearAnalysis)
def analyse_year(
    year: Optional[int] = None,
    include_business: bool = False,
    db: Session = Depends(get_db),
    user: models.User = Depends(get_current_user),
):
    """One row per pay cycle that starts in the given year, plus the year's totals by category."""
    l = user.ledger
    sd = l.cycle_start_day or 1
    year = year or date.today().year
    cats = {c.id: c for c in db.query(models.Category).filter(models.Category.ledger_id == l.id)}
    months, cat_total = [], defaultdict(lambda: ZERO)
    income_total = spend_total = ZERO
    for m in range(1, 13):
        start, end = cycle_bounds(sd, year, m)
        if start > __import__("datetime").datetime.now():
            break
        d = period_data(db, l.id, start, end, include_business)
        if d.income == 0 and d.expense == 0 and not d.expenses:
            continue
        months.append(schemas.CycleRow(
            month=m, period_start=start.strftime("%Y-%m-%d"), period_end=(end - timedelta(days=1)).strftime("%Y-%m-%d"),
            income=d.income, expense=d.expense, saved=d.income - d.expense,
        ))
        income_total += d.income
        spend_total += d.expense
        for cid, (amt, _) in _by_category(d, cats).items():
            cat_total[cid] += amt
    n = max(len(months), 1)
    categories = [
        schemas.CategoryStat(category_id=cid, name=cats[cid].name if cid in cats else "未分类",
                             icon=cats[cid].icon if cid in cats else "❓", total=amt, count=0,
                             share=float(amt / spend_total) if spend_total > 0 else 0, previous_total=ZERO, change_pct=None)
        for cid, amt in sorted(cat_total.items(), key=lambda kv: kv[1], reverse=True) if amt > 0
    ]
    best = max(months, key=lambda r: r.saved, default=None)
    worst = min(months, key=lambda r: r.saved, default=None)
    return schemas.YearAnalysis(
        year=year, income=income_total, expense=spend_total, saved=income_total - spend_total,
        saving_rate=float((income_total - spend_total) / income_total) if income_total > 0 else 0.0,
        avg_monthly_expense=(spend_total / n).quantize(Decimal("0.01")), months=months, categories=categories,
        best_month=best.month if best else None, worst_month=worst.month if worst else None,
    )


# ---- "my car": what keeping it on the road costs
CAR_BUCKETS = [
    ("fuel", "加油", True, re.compile("加油|石化|石油|海油|壳牌|协和石油")),
    ("repair", "保养维修", True, re.compile("保养|机油|修车|汽修|汽车服务|汽配|轮胎|4S|维修|钣金|养护|刹车|蓄电池|电瓶|正时|变速箱|分动箱|差速器|离合|积碳|挖煤|机脚|废气阀|涡轮|发动机")),
    ("insurance", "保险", True, re.compile("车险|保险|人保|太保|平安财")),
    ("parking", "停车", False, re.compile("停车|车位|车场|P云")),
    ("wash", "洗车", False, re.compile("洗车|汽车美容")),
    ("toll", "过路费", False, re.compile("ETC|高速|路桥|过路|服务区")),
    ("parts", "配件", False, re.compile("车品|汽车用品|踏板|脚踏|配件|车饰|车友|特调|改装|调校")),
]


def car_bucket(merchant: Optional[str], text: Optional[str]):
    hay = f"{merchant or ''} {text or ''}"
    for key, label, core, rx in CAR_BUCKETS:
        if rx.search(hay):
            return key, label, core
    return "other", "其他用车", False


@router.get("/car")
def car_costs(range: str = "12m", db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    """Fuel, upkeep and insurance are the 'core' of what the car costs; parking, washing, tolls, parts and the rest are
    shown beside it so the user can decide later what counts. range: 'year' (this calendar year) or '12m'."""
    from app.clock import now_local
    from app.stats import refunded_map
    today = now_local().date()
    start = date(today.year, 1, 1) if range == "year" else today - timedelta(days=365)
    mine = db.query(models.Category).filter(models.Category.ledger_id == user.ledger_id, models.Category.name == "我的车").first()
    refunds = refunded_map(db, user.ledger_id)
    rows = []
    if mine:
        rows = (db.query(models.Transaction)
                .filter(models.Transaction.ledger_id == user.ledger_id, models.Transaction.category_id == mine.id,
                        models.Transaction.tx_type == models.TxType.expense, models.Transaction.is_transfer.is_(False),
                        models.Transaction.refund_of_id.is_(None), models.Transaction.occurred_at >= datetime.combine(start, datetime.min.time()))
                .order_by(models.Transaction.occurred_at.desc()).all())
    from app.fixed import item_for, load
    fixed = load(db.get(models.Ledger, user.ledger_id))
    buckets = {k: {"key": k, "label": l, "core": c, "total": ZERO, "count": 0, "items": []} for k, l, c, _ in CAR_BUCKETS}
    buckets["other"] = {"key": "other", "label": "其他用车", "core": False, "total": ZERO, "count": 0, "items": []}
    monthly = defaultdict(lambda: {"fuel": ZERO, "repair": ZERO, "other": ZERO})
    for t in rows:
        eff = t.amount - refunds.get(t.id, ZERO)
        if eff <= 0:
            continue
        key, _label, _core = car_bucket(t.merchant, t.raw_text)
        if key == "other":                     # the user's own "parking fee" fixed item (paid to a property company, say)
            fx = item_for(fixed, t.amount, t.merchant, t.raw_text or "", t.occurred_at)
            if fx and "停车" in fx.get("name", ""):
                key = "parking"
        b = buckets[key]
        b["total"] += eff
        b["count"] += 1
        b["items"].append({"id": t.id, "date": t.occurred_at.strftime("%Y-%m-%d"), "merchant": _shop(t.merchant), "amount": eff})
        m = monthly[t.occurred_at.strftime("%Y-%m")]
        m["fuel" if key == "fuel" else "repair" if key in ("repair", "insurance") else "other"] += eff
    core_total = sum((b["total"] for b in buckets.values() if b["core"]), ZERO)
    all_total = sum((b["total"] for b in buckets.values()), ZERO)
    fuel = buckets["fuel"]
    first = min((datetime.strptime(i["date"], "%Y-%m-%d").date() for b in buckets.values() for i in b["items"]), default=today)
    return {
        "since": start.isoformat(), "first_record": first.isoformat(), "range": range,
        "core_total": core_total, "all_total": all_total,
        "buckets": sorted(buckets.values(), key=lambda b: (-int(b["core"]), -b["total"])),
        "monthly": [{"month": k, **v} for k, v in sorted(monthly.items())],
        "fuel": {"count": fuel["count"], "total": fuel["total"],
                 "avg": (fuel["total"] / fuel["count"]).quantize(Decimal("0.01")) if fuel["count"] else ZERO,
                 "max": max((i["amount"] for i in fuel["items"]), default=ZERO)},
    }
