from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app import models, schemas
from app.database import get_db
from app.deps import get_current_user
from app.reports import generate_for, to_out

router = APIRouter(prefix="/reports", tags=["reports"])


@router.get("/latest", response_model=schemas.ReportOut)
def latest(db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    """The newest daily analysis (written by the server's own schedule). A ledger that has none yet gets one now."""
    row = (
        db.query(models.AnalysisReport)
        .filter(models.AnalysisReport.ledger_id == user.ledger_id)
        .order_by(models.AnalysisReport.report_date.desc())
        .first()
    )
    if row is None:
        row = generate_for(db, user.ledger)
    return to_out(row)


@router.get("/history", response_model=list[schemas.ReportOut])
def history(limit: int = 30, db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    rows = (
        db.query(models.AnalysisReport)
        .filter(models.AnalysisReport.ledger_id == user.ledger_id)
        .order_by(models.AnalysisReport.report_date.desc())
        .limit(min(max(limit, 1), 120))
        .all()
    )
    return [to_out(r) for r in rows]
