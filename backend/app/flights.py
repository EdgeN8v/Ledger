"""Airline ticket SMS -> flights, kept in their own table (independent of the SMS inbox on the phone). They are
only a record now: they no longer open trips or turn into transactions."""
import re
from datetime import date, datetime, timedelta
from decimal import Decimal

from sqlalchemy.orm import Session

from app import models

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


# A travel site's "ticket issued" message names only the two airports and the order tail - no date, no flight number
CTRIP_ISSUED = re.compile(r"订单尾号(\d+)[，,]\s*([^，,\-－]{1,12}?)[-－]([^，,\-－]{1,12}?)[，,][^，,]*?机票出票成功")


def is_issued_note(text: str) -> bool:
    """'Order ...: Beijing-Shanghai, ticket issued': a ticket message that is not a payment and carries no flight details."""
    return bool(CTRIP_ISSUED.search(text))


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
