from fastapi import FastAPI

from app.database import Base, SessionLocal, engine
from app.maintenance import ensure_columns, ignore_mileage_notes, fix_keyword_conflicts, normalise_merchant_rules, apply_fixed_items, merge_car_and_transport, backfill_balances, flag_existing_refunds, recategorize_uncategorized, relink_orphan_refunds, sync_default_categories, sync_refund_categories
from app.config import settings
from app.reports import start_scheduler
from app.routers import accounts, analysis, auth, categories, reports, transactions, ingest, plan, trips

if not settings.database_url.startswith("sqlite") and settings.jwt_secret.startswith("change-me"):
    raise RuntimeError("JWT_SECRET must be set to a real random value outside local development")

Base.metadata.create_all(bind=engine)

with SessionLocal() as _db:
    ensure_columns(_db)
    ignore_mileage_notes(_db)
    flag_existing_refunds(_db)
    normalise_merchant_rules(_db)
    fix_keyword_conflicts(_db)
    merge_car_and_transport(_db)
    sync_default_categories(_db)
    recategorize_uncategorized(_db)
    apply_fixed_items(_db)
    backfill_balances(_db)
    relink_orphan_refunds(_db)
    sync_refund_categories(_db)

app = FastAPI(title="Ledger API")

app.include_router(auth.router)
app.include_router(categories.router)
app.include_router(transactions.router)
app.include_router(ingest.router)
app.include_router(accounts.router)
app.include_router(plan.router)
app.include_router(analysis.router)
app.include_router(reports.router)
app.include_router(trips.router)

if settings.scheduler_enabled:
    start_scheduler()


@app.get("/health")
def health():
    return {"status": "ok"}
