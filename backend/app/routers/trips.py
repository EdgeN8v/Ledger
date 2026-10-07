"""Trips: a weekend in Huangshan, the National Day drive home... A trip is a name + a date range. Spending of the
usual travel kinds inside it (fuel, tolls, parking, meals, tickets) is tagged to the trip automatically.

The money still counts in the pay cycle's totals (it was really spent), but the savings plan keeps it out of the
"pace" forecast, so one trip does not make an ordinary month look like a runaway one.
"""
import re
from collections import defaultdict
from datetime import date, datetime, timedelta
from decimal import Decimal
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app import models, schemas
from app.database import get_db
from app.deps import get_current_user
from app.fixed import item_for, load
from app.stats import refunded_map

router = APIRouter(prefix="/trips", tags=["trips"])
ZERO = Decimal(0)
TRIP_CATEGORIES = ("车辆出行", "餐饮", "娱乐", "其他支出")        # what a trip pulls in by default
STRONG = re.compile(r"ETC|高速|景区|景点|门票|酒店|民宿|旅行社|旅游|携程|飞猪|宾馆|客栈|度假")
FUEL = re.compile(r"加油|海油|石化|石油|充电|服务区")


def _d(s: str) -> date:
    try:
        return datetime.strptime(s, "%Y-%m-%d").date()
    except ValueError:
        raise HTTPException(400, "date must be YYYY-MM-DD")


def _eligible(db: Session, ledger: models.Ledger, start: date, end: date):
    """Expenses inside the range that look like travel spending and are not already in another trip / fixed item."""
    cats = {c.id: c.name for c in db.query(models.Category).filter(models.Category.ledger_id == ledger.id)}
    items = load(ledger)
    T = models.Transaction
    rows = (
        db.query(T)
        .filter(T.ledger_id == ledger.id, T.tx_type == models.TxType.expense, T.is_transfer.is_(False),
                T.refund_of_id.is_(None), T.occurred_at >= datetime.combine(start, datetime.min.time()),
                T.occurred_at < datetime.combine(end + timedelta(days=1), datetime.min.time()))
        .all()
    )
    out = []
    for t in rows:
        if cats.get(t.category_id) not in TRIP_CATEGORIES:
            continue
        if item_for(items, t.amount, t.merchant, t.raw_text or "", t.occurred_at):
            continue                                   # rent, parking subscription... are fixed items
        out.append(t)
    return out


def _summary(db: Session, trip: models.Trip) -> schemas.TripOut:
    refunds = refunded_map(db, trip.ledger_id)
    cats = {c.id: c for c in db.query(models.Category).filter(models.Category.ledger_id == trip.ledger_id)}
    rows = db.query(models.Transaction).filter(models.Transaction.trip_id == trip.id).all()
    total, by = ZERO, defaultdict(lambda: ZERO)
    for t in rows:
        eff = t.amount - refunds.get(t.id, ZERO)
        if eff <= 0:
            continue
        total += eff
        by[t.category_id] += eff
    return schemas.TripOut(
        id=trip.id, name=trip.name, start_date=trip.start_date, end_date=trip.end_date, total=total, count=len(rows),
        by_category=[
            schemas.TripCategory(name=cats[c].name if c in cats else "未分类", icon=cats[c].icon if c in cats else "❓", total=v)
            for c, v in sorted(by.items(), key=lambda kv: kv[1], reverse=True)
        ],
    )


def retag(db: Session, trip: models.Trip) -> None:
    ledger = db.get(models.Ledger, trip.ledger_id)
    db.query(models.Transaction).filter(models.Transaction.trip_id == trip.id).update({"trip_id": None})
    for t in _eligible(db, ledger, _d(trip.start_date), _d(trip.end_date)):
        if t.trip_id is None:
            t.trip_id = trip.id
    db.commit()


@router.get("", response_model=list[schemas.TripOut])
def list_trips(db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    trips = db.query(models.Trip).filter(models.Trip.ledger_id == user.ledger_id).order_by(models.Trip.start_date.desc()).all()
    return [_summary(db, t) for t in trips]


@router.post("", response_model=schemas.TripOut)
def create_trip(payload: schemas.TripIn, db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    if _d(payload.end_date) < _d(payload.start_date):
        raise HTTPException(400, "end before start")
    trip = models.Trip(ledger_id=user.ledger_id, name=payload.name.strip() or "出行",
                       start_date=payload.start_date, end_date=payload.end_date)
    db.add(trip)
    db.commit()
    retag(db, trip)
    db.refresh(trip)
    return _summary(db, trip)


@router.put("/{trip_id}", response_model=schemas.TripOut)
def update_trip(trip_id: int, payload: schemas.TripIn, db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    trip = db.query(models.Trip).filter(models.Trip.id == trip_id, models.Trip.ledger_id == user.ledger_id).first()
    if not trip:
        raise HTTPException(404, "trip not found")
    trip.name, trip.start_date, trip.end_date = payload.name.strip() or trip.name, payload.start_date, payload.end_date
    db.commit()
    retag(db, trip)
    return _summary(db, trip)


@router.delete("/{trip_id}", status_code=204)
def delete_trip(trip_id: int, db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    """Only removes the grouping: the transactions themselves are untouched."""
    trip = db.query(models.Trip).filter(models.Trip.id == trip_id, models.Trip.ledger_id == user.ledger_id).first()
    if not trip:
        raise HTTPException(404, "trip not found")
    db.query(models.Transaction).filter(models.Transaction.trip_id == trip.id).update({"trip_id": None})
    db.delete(trip)
    db.commit()


@router.post("/{trip_id}/remove/{tx_id}", status_code=204)
def remove_from_trip(trip_id: int, tx_id: int, db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    tx = db.query(models.Transaction).filter(models.Transaction.id == tx_id, models.Transaction.ledger_id == user.ledger_id,
                                             models.Transaction.trip_id == trip_id).first()
    if tx:
        tx.trip_id = None
        db.commit()


@router.post("/suggestions/dismiss", status_code=204)
def dismiss_suggestion(payload: schemas.TripSuggestionRef, db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    """"This is not a trip": remember the range so it is not offered again."""
    _d(payload.start_date), _d(payload.end_date)
    db.add(models.TripDismissal(ledger_id=user.ledger_id, start_date=payload.start_date, end_date=payload.end_date))
    db.commit()


@router.get("/suggestions", response_model=list[schemas.TripSuggestion])
def suggestions(db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    """Days that look like a trip: tolls, scenic spots, hotels, travel agencies, plus fuel on the way. Consecutive
    such days are grouped; ranges that already belong to a trip are not offered again."""
    ledger = user.ledger
    since = datetime.now() - timedelta(days=150)
    T = models.Transaction
    rows = (
        db.query(T)
        .filter(T.ledger_id == ledger.id, T.tx_type == models.TxType.expense, T.is_transfer.is_(False),
                T.refund_of_id.is_(None), T.occurred_at >= since)
        .all()
    )
    strong_days, fuel_days = defaultdict(int), defaultdict(int)
    for t in rows:
        hay = f"{t.merchant or ''} {t.raw_text or ''}"
        if STRONG.search(hay):
            strong_days[t.occurred_at.date()] += 1
        elif FUEL.search(hay):
            fuel_days[t.occurred_at.date()] += 1
    days = sorted(d for d in strong_days if strong_days[d] >= 1)
    existing = [(_d(t.start_date), _d(t.end_date)) for t in db.query(models.Trip).filter(models.Trip.ledger_id == ledger.id)]
    existing += [(_d(x.start_date), _d(x.end_date)) for x in db.query(models.TripDismissal).filter(models.TripDismissal.ledger_id == ledger.id)]
    groups, cur = [], []
    for d in days:
        if cur and (d - cur[-1]).days > 1:
            groups.append(cur)
            cur = []
        cur.append(d)
    if cur:
        groups.append(cur)
    out = []
    for g in groups:
        start, end = g[0], g[-1]
        # a trip usually starts the day before the first toll/hotel (fuel up) and ends the day after
        if fuel_days.get(start - timedelta(days=1)):
            start -= timedelta(days=1)
        if any(not (end < a or start > b) for a, b in existing):
            continue
        n_strong = sum(strong_days[d] for d in g)
        if n_strong < 3:
            continue
        est = sum((t.amount for t in _eligible(db, ledger, start, end)), ZERO)
        out.append(schemas.TripSuggestion(
            start_date=start.strftime("%Y-%m-%d"), end_date=end.strftime("%Y-%m-%d"),
            reason=f"{n_strong} 笔过路费/景区/酒店类消费", estimate=est,
        ))
    return sorted(out, key=lambda s: s.start_date, reverse=True)[:5]
