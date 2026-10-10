"""Car upkeep: what is due next, in kilometres and in time, from the odometer and the services done so far.

The odometer is told to the app now and then; between two readings the distance is estimated from how fast the car has
really been driven. Each upkeep item has an interval (km and/or months). A service entry restarts its clock; an item
that was never recorded can carry a fixed "due at" figure instead.
"""
from datetime import date, datetime, timedelta
from decimal import Decimal
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app import models
from app.clock import now_local
from app.database import get_db
from app.deps import get_current_user

router = APIRouter(prefix="/carcare", tags=["carcare"])

# key, name, interval_km, interval_months, hint. Generic defaults; every number can be changed per ledger.
DEFAULT_ITEMS = [
    ("oil", "机油机滤", 8000, 12, "按里程或一年, 哪个先到"),
    ("spark", "火花塞", 20000, None, ""),
    ("brake_fluid", "刹车油", None, 24, "两年一换"),
    ("coolant", "冷却液", 50000, 60, "五年或五万公里"),
    ("gearbox", "变速箱油", 60000, None, "一般六到八万公里"),
    ("belt", "皮带", 130000, None, "一般十三到十五万公里"),
    ("inspection", "年检", None, 12, ""),
]
OTHER = "other"
DEFAULT_RATE = 30.0                      # km/day when there are not yet two readings to learn it from


class OdometerIn(BaseModel):
    km: int
    on: Optional[str] = None


class ServiceIn(BaseModel):
    item: str = OTHER
    on: Optional[str] = None
    km: Optional[int] = None
    cost: Optional[Decimal] = None
    note: Optional[str] = None


class ItemIn(BaseModel):
    interval_km: Optional[int] = None
    interval_months: Optional[int] = None
    due_km: Optional[int] = None
    due_date: Optional[str] = None


def _d(s: Optional[str]) -> date:
    if not s:
        return now_local().date()
    try:
        return datetime.strptime(s, "%Y-%m-%d").date()
    except ValueError:
        raise HTTPException(400, "date must be YYYY-MM-DD")


def _add_months(d: date, n: int) -> date:
    m = d.month - 1 + n
    y, m = d.year + m // 12, m % 12 + 1
    return date(y, m, min(d.day, [31, 29 if y % 4 == 0 and (y % 100 or y % 400 == 0) else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][m - 1]))


def ensure_items(db: Session, ledger_id: int):
    items = db.query(models.CarItem).filter(models.CarItem.ledger_id == ledger_id).all()
    if not items:
        for i, (key, name, km, months, hint) in enumerate(DEFAULT_ITEMS):
            db.add(models.CarItem(ledger_id=ledger_id, key=key, name=name, interval_km=km, interval_months=months, note=hint or None, sort=i))
        db.commit()
        items = db.query(models.CarItem).filter(models.CarItem.ledger_id == ledger_id).all()
    return items


def _odometer(db: Session, ledger_id: int, today: date):
    rows = (db.query(models.CarOdometer).filter(models.CarOdometer.ledger_id == ledger_id)
            .order_by(models.CarOdometer.on_date, models.CarOdometer.id).all())
    if not rows:
        return None
    last = rows[-1]
    rate = DEFAULT_RATE
    recent = [r for r in rows if (last.on_date - r.on_date).days <= 300]
    first = recent[0]
    span = (last.on_date - first.on_date).days
    if span >= 30 and last.km > first.km:
        rate = (last.km - first.km) / span
    est = last.km + rate * max((today - last.on_date).days, 0)
    return {"km": last.km, "as_of": last.on_date.isoformat(), "per_day": round(rate, 1), "est_km": int(round(est, -1)), "readings": len(rows)}


def _service_out(s: models.CarService, names: dict):
    return {"id": s.id, "item": s.item_key, "name": names.get(s.item_key, "其他"), "date": s.on_date.isoformat(), "km": s.km,
            "cost": str(s.cost) if s.cost is not None else None, "note": s.note}


@router.get("")
def overview(db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    today = now_local().date()
    items = ensure_items(db, user.ledger_id)
    names = {i.key: i.name for i in items}
    odo = _odometer(db, user.ledger_id, today)
    services = (db.query(models.CarService).filter(models.CarService.ledger_id == user.ledger_id)
                .order_by(models.CarService.on_date.desc(), models.CarService.id.desc()).all())
    last_of = {}
    for s in services:
        last_of.setdefault(s.item_key, s)
    rate = odo["per_day"] if odo else DEFAULT_RATE
    est = odo["est_km"] if odo else None
    out = []
    for it in items:
        last = last_of.get(it.key)
        due_km = (last.km + it.interval_km) if last and last.km is not None and it.interval_km else (it.due_km if not last else None)
        due_date = None
        if last and it.interval_months:
            due_date = _add_months(last.on_date, it.interval_months)
        elif not last and it.due_date:
            due_date = it.due_date
        left_km = (due_km - est) if due_km is not None and est is not None else None
        left_days = (due_date - today).days if due_date else None
        by_km = int(left_days_from_km) if (left_days_from_km := (left_km / rate if left_km is not None and rate else None)) is not None else None
        days_left = min([d for d in (left_days, by_km) if d is not None], default=None)
        status = "unknown"
        if days_left is not None:
            soon = (left_km is not None and left_km <= min(max(1000, 0.1 * (it.interval_km or 0)), 3000)) or (left_days is not None and left_days <= 45)
            status = "overdue" if (left_km is not None and left_km <= 0) or (left_days is not None and left_days <= 0) else ("soon" if soon else "ok")
        out.append({
            "key": it.key, "name": it.name, "interval_km": it.interval_km, "interval_months": it.interval_months, "note": it.note,
            "last": _service_out(last, names) if last else None,
            "due_km": due_km, "due_date": due_date.isoformat() if due_date else None,
            "left_km": int(left_km) if left_km is not None else None, "left_days": left_days,
            "eta_date": (today + timedelta(days=by_km)).isoformat() if by_km is not None and by_km > 0 else None,
            "status": status, "_rank": days_left if days_left is not None else 10 ** 6,
        })
    out.sort(key=lambda x: x["_rank"])
    for x in out:
        del x["_rank"]
    return {"odometer": odo, "items": out, "history": [_service_out(s, names) for s in services[:40]],
            "choices": [{"key": i.key, "name": i.name} for i in sorted(items, key=lambda i: i.sort or 0)] + [{"key": OTHER, "name": "其他维修"}]}


@router.post("/odometer")
def set_odometer(body: OdometerIn, db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    if body.km <= 0:
        raise HTTPException(400, "km must be positive")
    d = _d(body.on)
    row = db.query(models.CarOdometer).filter(models.CarOdometer.ledger_id == user.ledger_id, models.CarOdometer.on_date == d).first()
    if row:
        row.km = body.km
    else:
        db.add(models.CarOdometer(ledger_id=user.ledger_id, on_date=d, km=body.km))
    db.commit()
    return _odometer(db, user.ledger_id, now_local().date())


@router.post("/services")
def add_service(body: ServiceIn, db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    items = ensure_items(db, user.ledger_id)
    if body.item != OTHER and body.item not in {i.key for i in items}:
        raise HTTPException(400, "unknown item")
    d = _d(body.on)
    km = body.km
    if km is None:
        odo = _odometer(db, user.ledger_id, d)
        km = odo["est_km"] if odo else None
    s = models.CarService(ledger_id=user.ledger_id, item_key=body.item, on_date=d, km=km, cost=body.cost, note=body.note)
    db.add(s)
    if body.km is not None:                                       # a km figure typed with a service is also an odometer reading
        row = db.query(models.CarOdometer).filter(models.CarOdometer.ledger_id == user.ledger_id, models.CarOdometer.on_date == d).first()
        if not row:
            db.add(models.CarOdometer(ledger_id=user.ledger_id, on_date=d, km=body.km))
    db.commit()
    return {"id": s.id}


@router.delete("/services/{sid}")
def delete_service(sid: int, db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    s = db.query(models.CarService).filter(models.CarService.id == sid, models.CarService.ledger_id == user.ledger_id).first()
    if not s:
        raise HTTPException(404)
    db.delete(s)
    db.commit()
    return {"ok": True}


@router.put("/items/{key}")
def edit_item(key: str, body: ItemIn, db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    ensure_items(db, user.ledger_id)
    it = db.query(models.CarItem).filter(models.CarItem.ledger_id == user.ledger_id, models.CarItem.key == key).first()
    if not it:
        raise HTTPException(404)
    data = body.model_dump(exclude_unset=True) if hasattr(body, "model_dump") else body.dict(exclude_unset=True)
    for k, v in data.items():
        setattr(it, k, _d(v) if k == "due_date" and v else (None if k == "due_date" else v))
    db.commit()
    return {"ok": True}
