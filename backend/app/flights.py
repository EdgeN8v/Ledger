"""Airline ticket SMS -> flights -> business trips.

Business travel here is flown with China Southern (CZ), China Eastern (MU, FM = Shanghai Airlines) and, rarely,
Spring (9C). Their confirmation SMS say when the ticket was ordered, when it leaves and from where to where, so a
trip no longer has to be created by hand:

  * a trip starts with a flight that leaves the HOME city and ends with the flight that lands back there;
  * until the return flight is known the trip is open and runs up to today;
  * a trip is made only when the user PAID for tickets or a hotel: a ticket someone else paid for costs nothing, so
    there is nothing to record (it is stored as a flight, but never becomes a trip or a transaction);
  * the flights live in their own table, independent of the SMS inbox on the phone: deleting the message later
    changes nothing here.
"""
import re
from datetime import date, datetime, timedelta
from decimal import Decimal
from typing import Optional

from sqlalchemy.orm import Session

from app import models
from app.clock import now_local

DEFAULT_HOME = "上海"
AIRLINE_PAY = re.compile(r"南方航空|南航|东方航空|东航|东美航旅|春秋|航空|机票")      # a payment to an airline / ticket seller (not any travel site)
HISTORY_DAYS = 14                                    # older finished journeys are not turned into trips
AIRLINES = ("CZ", "MU", "FM", "9C")                 # the carriers business travel is booked with
MILEAGE = re.compile(r"感谢您于\d{4}-\d{2}-\d{2}\s*乘坐.{2,12}航班.*累积里程")      # after-flight mileage note: not a payment

_CITY_OF_AIRPORT = {
    "虹桥": "上海", "浦东": "上海", "大兴": "北京", "首都": "北京", "南苑": "北京", "天河": "武汉", "双流": "成都", "天府": "成都",
    "白云": "广州", "宝安": "深圳", "咸阳": "西安", "萧山": "杭州", "禄口": "南京", "桃仙": "沈阳", "胶东": "青岛", "地窝堡": "乌鲁木齐",
    "长水": "昆明", "江北": "重庆", "新郑": "郑州", "高崎": "厦门", "黄花": "长沙", "中川": "兰州", "河东": "银川",
}
_KNOWN_CITIES = ("上海", "北京", "武汉", "成都", "广州", "深圳", "西安", "杭州", "南京", "沈阳", "青岛", "乌鲁木齐", "昆明", "重庆",
                 "郑州", "厦门", "长沙", "兰州", "银川", "嘉峪关", "天津", "合肥", "济南", "哈尔滨", "长春", "太原", "南昌", "福州", "贵阳", "南宁", "海口", "三亚", "拉萨", "西宁", "呼和浩特")


def city_of(place: str) -> str:
    """'上海(上海虹桥国际机场)', '北京大兴', '武汉T3', '上海虹桥T2' -> the city."""
    p = re.sub(r"[（(].*$", "", (place or "").strip())
    p = re.sub(r"T\d+$", "", p).strip()
    for c in _KNOWN_CITIES:
        if p.startswith(c):
            return c
    for ap, c in _CITY_OF_AIRPORT.items():
        if ap in p:
            return c
    return p


def _date_near(posted: datetime, month: int, day: int) -> date:
    """The ticket SMS gives month and day only: the year is the one that puts it within a sensible span of the SMS date."""
    cand = date(posted.year, month, day)
    if cand < posted.date() - timedelta(days=60):
        cand = date(posted.year + 1, month, day)
    return cand


def _dt(d: date, hhmm: str) -> datetime:
    h, m = hhmm.split(":")
    return datetime(d.year, d.month, d.day, int(h), int(m))


_CSAIR = re.compile(
    r"(?P<m>\d{1,2})月(?P<d>\d{1,2})日\s*(?P<fno>[A-Z0-9]{2}\d{3,4})航班\S*?[，,]\s*(?P<o>[^(（，,]+?)[(（][^)）]*[)）]\s*(?:航站楼\S*\s*)?"
    r"(?P<t1>\d{1,2}:\d{2})\s*-\s*(?P<dst>[^(（，,]+?)[(（][^)）]*[)）]\s*(?:航站楼\S*\s*)?(?P<t2>\d{1,2}:\d{2})")
_AGENT = re.compile(
    r"(?P<m>\d{1,2})月(?P<d>\d{1,2})日(?P<o>[^，,;；]+?)到(?P<dst>[^，,;；]+?)(?:[,，]经停[^，,;；]+)?[,，](?:东航|上航|南航|春秋|[^，,;；]{2,3}航)"
    r"(?P<fno>[A-Z0-9]{2}\d{3,4})[,，](?P<t1>\d{1,2}:\d{2})起飞-(?:(?P<m2>\d{1,2})月(?P<d2>\d{1,2})日)?(?P<t2>\d{1,2}:\d{2})到达")


def parse_flights(text: str, posted: datetime) -> list[dict]:
    """Flights of one ticket SMS (empty list = not a ticket SMS). Each: passenger, flight_no, origin, dest, depart_at,
    arrive_at, ordered_on, ticket_no, price (whole order, when the SMS states it)."""
    out: list[dict] = []
    if "支付成功" in text and "乘机人" in text and "航班" in text:                       # China Southern
        pax = re.search(r"乘机人([^，,\s]+)", text)
        tk = re.search(r"票号(\d+)", text)
        od = re.search(r"已于(\d{4})-(\d{2})-(\d{2})支付成功", text)
        ordered = date(int(od.group(1)), int(od.group(2)), int(od.group(3))) if od else posted.date()
        for m in _CSAIR.finditer(text):
            dep_day = _date_near(datetime.combine(ordered, datetime.min.time()), int(m["m"]), int(m["d"]))
            dep, arr = _dt(dep_day, m["t1"]), _dt(dep_day, m["t2"])
            if arr < dep:
                arr += timedelta(days=1)
            out.append(dict(passenger=pax.group(1) if pax else None, flight_no=m["fno"], origin=city_of(m["o"]), dest=city_of(m["dst"]),
                            depart_at=dep, arrive_at=arr, ordered_on=ordered, ticket_no=tk.group(1) if tk else None, price=None))
    elif "成功预定" in text and "乘机人" in text:                                      # the ticket agent used for China Eastern
        pax = re.search(r"乘机人([^(（，,\s]+)", text)
        tk = re.search(r"票号[:：]\s*(\d+)", text)
        pr = re.search(r"票价[:：]?\s*[(（]票价(\d+)\s*机建税(\d+)\s*燃油费(\d+)元[)）]/人\s*(\d+)元服务费", text)
        price = Decimal(sum(int(x) for x in pr.groups())) if pr else None
        for seg in re.split(r"[;；]", text):
            m = _AGENT.search(seg)
            if not m:
                continue
            dep_day = _date_near(posted, int(m["m"]), int(m["d"]))
            dep = _dt(dep_day, m["t1"])
            arr_day = _date_near(posted, int(m["m2"]), int(m["d2"])) if m["m2"] else dep_day
            arr = _dt(arr_day, m["t2"])
            if arr < dep:
                arr += timedelta(days=1)
            out.append(dict(passenger=pax.group(1) if pax else None, flight_no=m["fno"], origin=city_of(m["o"]), dest=city_of(m["dst"]),
                            depart_at=dep, arrive_at=arr, ordered_on=posted.date(), ticket_no=tk.group(1) if tk else None, price=price))
    return [f for f in out if f["flight_no"][:2] in AIRLINES]


_AD = re.compile("升级|优惠|立减|满减|领券|红包|促销|折扣|抢购|拒收请回复|退订回|回T")


def looks_like_ticket(text: str) -> bool:
    """A message about an air ticket (issued / booked) - used to keep the ones the parser does not understand yet."""
    return "航班" in text and bool(re.search(r"机票|出票|乘机人|订单", text)) and not _AD.search(text)


def store_flights(db: Session, ledger_id: int, flights: list[dict]) -> int:
    """Keep the flights of the account holder. Returns how many are new."""
    ledger = db.get(models.Ledger, ledger_id)
    names = [n.strip() for n in (ledger.owner_names or "").split(",") if n.strip()]
    added = 0
    for f in flights:
        if names and f["passenger"] and not any(n in f["passenger"] for n in names):
            continue                                                  # somebody else's ticket
        have = (db.query(models.Flight)
                .filter(models.Flight.ledger_id == ledger_id, models.Flight.flight_no == f["flight_no"], models.Flight.depart_at == f["depart_at"])
                .first())
        if have:
            if f["price"] and not have.price:
                have.price = f["price"]
            continue
        db.add(models.Flight(ledger_id=ledger_id, **f))
        added += 1
    db.flush()
    return added


def _journeys(flights: list, home: str) -> list[dict]:
    """Home -> ... -> home chains. Flights that start away from home with no known departure (the tail of a trip that
    began before the data does) are not made into a trip: their start date is unknown."""
    out, cur = [], None
    for f in flights:
        if cur is not None and f.origin != cur[-1].dest:
            out.append({"flights": cur, "closed": False, "broken": True})   # a leg is missing between these two: stop at the last known landing
            cur = None
        if cur is None:
            if f.origin == home:
                cur = [f]
            continue
        cur.append(f)
        if f.dest == home:
            out.append({"flights": cur, "closed": True})
            cur = None
    if cur:
        out.append({"flights": cur, "closed": False})
    return out


def _paid_by_user(db: Session, ledger, fl: list, start: date, end: date, trip_id: Optional[int] = None) -> bool:
    """A trip exists because the user PAID for tickets or a hotel. Tickets booked and paid by someone else cost nothing
    and leave nothing to record. Evidence: a payment of exactly the ticket price around the order day, a flight / hotel
    booking payment around the order day, or one during the trip itself."""
    from app.routers.trips import BOOKING
    T = models.Transaction
    day = datetime.min.time()
    for f in fl:
        lo, hi = datetime.combine(f.ordered_on - timedelta(days=1), day), datetime.combine(f.ordered_on + timedelta(days=2), day)
        rows = (db.query(T).filter(T.ledger_id == ledger.id, T.tx_type == models.TxType.expense, T.is_transfer.is_(False),
                                   T.refund_of_id.is_(None), (T.trip_id.is_(None) | (T.trip_id == trip_id)), T.occurred_at >= lo, T.occurred_at < hi).all())
        for t in rows:
            if (f.price and t.amount == f.price) or AIRLINE_PAY.search(f"{t.merchant or ''} {t.raw_text or ''}"):
                return True
    rows = (db.query(T).filter(T.ledger_id == ledger.id, T.tx_type == models.TxType.expense, T.is_transfer.is_(False),
                               T.refund_of_id.is_(None), (T.trip_id.is_(None) | (T.trip_id == trip_id)),
                               T.occurred_at >= datetime.combine(start, day), T.occurred_at < datetime.combine(end + timedelta(days=1), day)).all())
    return any(BOOKING.search(f"{t.merchant or ''} {t.raw_text or ''}") for t in rows)


def sync_flight_trips(db: Session, ledger_id: int) -> None:
    """Create / extend the business trips that the stored flights describe. Safe to run any number of times."""
    from app.routers.trips import retag                        # lazy: trips imports this module too

    ledger = db.get(models.Ledger, ledger_id)
    home = city_of(getattr(ledger, "home_city", None) or DEFAULT_HOME)
    flights = (db.query(models.Flight).filter(models.Flight.ledger_id == ledger_id).order_by(models.Flight.depart_at).all())
    if not flights:
        return
    today = now_local().date()
    trips = db.query(models.Trip).filter(models.Trip.ledger_id == ledger_id, models.Trip.kind == "business").all()
    by_key = {t.auto_key: t for t in trips if t.auto_key}
    dismissed = {d.end_date[5:] for d in db.query(models.TripDismissal).filter(models.TripDismissal.ledger_id == ledger_id,
                                                                              models.TripDismissal.end_date.like("auto:%"))}
    last_biz = next((t for t in sorted(trips, key=lambda t: t.start_date, reverse=True) if t.daily_allowance or t.commute_allowance), None)
    changed = []
    for j in _journeys(flights, home):
        fl = j["flights"]
        start = fl[0].depart_at.date()
        last = fl[-1].arrive_at.date()
        end = last if (j["closed"] or j.get("broken")) else max(start, today)
        key = f"{fl[0].flight_no}@{start:%Y%m%d}"
        if key in dismissed:
            continue
        trip = by_key.get(key)
        if trip is None:                                       # a trip the user already made by hand for these days
            trip = next((t for t in trips if not t.auto_key and abs((datetime.strptime(t.start_date, "%Y-%m-%d").date() - start).days) <= 3), None)
            if trip:
                trip.auto_key = key
        away = [f.dest for f in fl if f.dest != home]
        if (trip is not None and trip.name.startswith("出差·") and not trip.dates_locked and trip.settled_at is None
                and not db.query(models.Transaction).filter(models.Transaction.trip_id == trip.id,
                                                            (models.Transaction.trip_manual.is_(True)) | (models.Transaction.is_reimbursement.is_(True))).first()
                and not _paid_by_user(db, ledger, fl, start, end, trip.id)):
            # made by an earlier version before "only what the user paid for": nothing was spent, so nothing to record
            db.query(models.Flight).filter(models.Flight.trip_id == trip.id).update({"trip_id": None})
            db.query(models.Transaction).filter(models.Transaction.trip_id == trip.id).update({"trip_id": None})
            db.delete(trip)
            db.flush()
            continue
        if trip is None and ((j["closed"] and last < today - timedelta(days=HISTORY_DAYS)) or (not j["closed"] and last < today - timedelta(days=30))):
            continue                                           # history: it was reimbursed long ago, do not bring it back as "to be reimbursed"
        if trip is None and not _paid_by_user(db, ledger, fl, start, end):
            continue                                           # nothing was spent by the user: nothing to record
        if trip is None:
            trip = models.Trip(ledger_id=ledger_id, name="出差·" + (away[-1] if away else "外地"), start_date=start.isoformat(), end_date=end.isoformat(),
                               kind="business", auto_tag=True, auto_key=key,
                               daily_allowance=last_biz.daily_allowance if last_biz else Decimal(180),
                               commute_allowance=last_biz.commute_allowance if last_biz else Decimal(80))
            db.add(trip)
            db.flush()
            trip.open_end = not j["closed"]
            changed.append(trip)
        elif trip.settled_at is None and not trip.dates_locked:
            if (trip.start_date, trip.end_date) != (start.isoformat(), end.isoformat()):
                trip.start_date, trip.end_date = start.isoformat(), end.isoformat()
                changed.append(trip)
            trip.open_end = not j["closed"]
        for f in fl:
            f.trip_id = trip.id
        # the ticket payment: the only way a ticket becomes money. Same amount as the order, around the order day.
        for f in fl:
            if not f.price:
                continue
            lo = datetime.combine(f.ordered_on - timedelta(days=1), datetime.min.time())
            hi = datetime.combine(f.ordered_on + timedelta(days=2), datetime.min.time())
            tx = (db.query(models.Transaction)
                  .filter(models.Transaction.ledger_id == ledger_id, models.Transaction.tx_type == models.TxType.expense,
                          models.Transaction.amount == f.price, models.Transaction.occurred_at >= lo, models.Transaction.occurred_at < hi,
                          models.Transaction.trip_id.is_(None)).first())
            if tx:
                tx.trip_id, tx.trip_manual = trip.id, True        # hand-picked class: a later retag must not drop it
    db.commit()
    for t in changed:
        retag(db, t)
