"""The daily analysis: the server writes it by itself once a day, nobody has to ask for it.

A small background thread wakes up every 20 minutes; once it is past 06:00 local time and today's report does
not exist yet, it builds one per ledger from the data (spending so far, pace against the savings plan, unusual
purchases, probable subscriptions) and stores it. The phone just reads the latest one. The maths is plain
arithmetic over the user's own transactions, so it runs fine inside the API container on the NAS - nothing heavy
that would need the Orange Pi.
"""
import json
import statistics
import threading
import time
from collections import defaultdict
from datetime import datetime, timedelta
from decimal import Decimal

from sqlalchemy.orm import Session

from app import models, schemas
from app.clock import now_local
from app.database import SessionLocal
from app.routers.analysis import _shop, cycle_analysis
from app.routers.plan import status_for
from app.stats import period_data

ZERO = Decimal(0)
RUN_AFTER_HOUR = 6
CHECK_EVERY_SECONDS = 20 * 60


def _alerts(db: Session, ledger: models.Ledger, now: datetime, status) -> list[str]:
    alerts = []
    if status.state == "over":
        alerts.append(f"本账期已经超出预算 ¥{abs(status.remaining):,.0f}, 离目标存款 ¥{status.savings_target:,.0f} 越来越远")
    elif status.state == "at_risk":
        alerts.append(f"照现在的速度本账期预计花 ¥{status.projected_spend:,.0f}, 会超出预算, 每天最多花 ¥{status.daily_allowance:,.0f} 才守得住")

    # unusual purchases in the last two days compared with the user's own usual size
    data = period_data(db, ledger.id, now - timedelta(days=90), now + timedelta(days=1))
    amounts = [float(e) for _, e in data.expenses]
    if len(amounts) >= 20:
        usual = statistics.median(amounts)
        limit = max(usual * 4, 300)
        seen = defaultdict(int)
        for t, _ in data.expenses:
            seen[_shop(t.merchant)] += 1
        for t, eff in data.expenses:
            if seen[_shop(t.merchant)] >= 3:
                continue                      # a place you pay regularly (fuel, parking...) is not "unusual"
            if t.occurred_at >= now - timedelta(days=2) and float(eff) >= limit:
                alerts.append(f"{t.occurred_at.strftime('%m月%d日')} {_shop(t.merchant)} ¥{eff:,.0f}, 远高于你平时的单笔(常见 ¥{usual:,.0f})")
    return alerts[:5]


def _subscriptions(db: Session, ledger: models.Ledger, now: datetime) -> list[str]:
    """Same shop, same amount, roughly monthly, at least three times: probably a subscription."""
    data = period_data(db, ledger.id, now - timedelta(days=150), now + timedelta(days=1))
    groups = defaultdict(list)
    for t, eff in data.expenses:
        groups[(_shop(t.merchant), eff)].append(t.occurred_at)
    found = []
    for (shop, amt), dates in groups.items():
        dates.sort()
        if len(dates) >= 3 and amt >= 5:
            gaps = [(b - a).days for a, b in zip(dates, dates[1:])]
            if all(25 <= g <= 36 for g in gaps):
                found.append(f"{shop} 每月 ¥{amt:,.2f}, 已连续 {len(dates)} 个月, 还需要吗?")
    return found[:5]


def build_report(db: Session, ledger: models.Ledger, now: datetime) -> dict:
    ana = cycle_analysis(db, ledger)
    st = status_for(db, ledger)
    today = now.date()
    day_totals = {d.date: d.total for d in ana.daily}
    yesterday = day_totals.get((today - timedelta(days=1)).strftime("%Y-%m-%d"), ZERO)
    today_spend = day_totals.get(today.strftime("%Y-%m-%d"), ZERO)

    headline = f"昨天花了 ¥{yesterday:,.0f}, 本账期已花 ¥{ana.total_expense:,.0f}"
    if st.state != "no_plan":
        headline += (f", 预算还剩 ¥{st.remaining:,.0f} (每天可花 ¥{st.daily_allowance:,.0f})" if st.remaining > 0
                     else f", 已超出预算 ¥{abs(st.remaining):,.0f}")

    return {
        "report_date": today.strftime("%Y-%m-%d"),
        "headline": headline,
        "period_start": ana.period_start,
        "period_end": ana.period_end,
        "yesterday_spend": str(yesterday),
        "today_spend": str(today_spend),
        "avg_daily": str(ana.avg_daily),
        "plan_state": st.state,
        "remaining": str(st.remaining),
        "daily_allowance": str(st.daily_allowance),
        "insights": ana.insights,
        "alerts": _alerts(db, ledger, now, st),
        "subscriptions": _subscriptions(db, ledger, now),
    }


def generate_for(db: Session, ledger: models.Ledger, now: datetime = None) -> models.AnalysisReport:
    now = now or now_local()
    payload = build_report(db, ledger, now)
    row = (
        db.query(models.AnalysisReport)
        .filter(models.AnalysisReport.ledger_id == ledger.id, models.AnalysisReport.report_date == payload["report_date"])
        .first()
    )
    if row is None:
        row = models.AnalysisReport(ledger_id=ledger.id, report_date=payload["report_date"])
        db.add(row)
    row.generated_at = now
    row.headline = payload["headline"]
    row.payload = json.dumps(payload, ensure_ascii=False)
    db.commit()
    return row


def to_out(row: models.AnalysisReport) -> schemas.ReportOut:
    p = json.loads(row.payload)
    return schemas.ReportOut(generated_at=row.generated_at, **{k: p[k] for k in schemas.ReportOut.model_fields if k != "generated_at" and k in p})


def run_due_reports() -> int:
    """One pass of the daily job. Returns how many ledgers got a fresh report."""
    now = now_local()
    made = 0
    with SessionLocal() as db:
        for ledger in db.query(models.Ledger).all():
            have = (
                db.query(models.AnalysisReport.id)
                .filter(models.AnalysisReport.ledger_id == ledger.id,
                        models.AnalysisReport.report_date == now.strftime("%Y-%m-%d"))
                .first()
            )
            newest_exists = db.query(models.AnalysisReport.id).filter(models.AnalysisReport.ledger_id == ledger.id).first()
            # the daily one after 06:00; and straight away if the ledger has never had any report
            if (not have and now.hour >= RUN_AFTER_HOUR) or not newest_exists:
                try:
                    generate_for(db, ledger, now)
                    made += 1
                except Exception:
                    db.rollback()
    return made


def start_scheduler() -> None:
    def loop():
        while True:
            try:
                run_due_reports()
            except Exception:
                pass
            time.sleep(CHECK_EVERY_SECONDS)

    threading.Thread(target=loop, name="daily-analysis", daemon=True).start()
