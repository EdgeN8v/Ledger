"""Debts: how much is owed to the spouse (or anyone) and how much of it has been paid back."""
import json
from datetime import date

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app import loans, models, schemas
from app.clock import now_local
from app.database import get_db
from app.deps import get_current_user

router = APIRouter(prefix="/loans", tags=["loans"])


@router.get("", response_model=list[schemas.LoanStatus])
def list_loans(db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    return loans.status_for(db, user.ledger, now_local().date())


@router.put("", response_model=list[schemas.LoanStatus])
def set_loans(payload: schemas.LoansIn, db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    """Replace the list. Each item: who (required), start (YYYY-MM-DD), name, principal, monthly."""
    clean = []
    for it in payload.loans:
        if not str(it.get("who") or "").strip():
            raise HTTPException(400, "每笔借款要写明是谁")
        if it.get("start"):
            try:
                date.fromisoformat(it["start"])
            except ValueError:
                raise HTTPException(400, "start 要写成 YYYY-MM-DD")
        clean.append({k: it[k] for k in ("name", "who", "start", "principal", "monthly") if it.get(k) not in (None, "")})
    user.ledger.loans = json.dumps(clean, ensure_ascii=False)
    db.commit()
    return loans.status_for(db, user.ledger, now_local().date())
