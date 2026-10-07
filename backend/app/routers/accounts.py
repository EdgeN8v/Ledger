from decimal import Decimal

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app import models, schemas
from app.clock import now_local
from app.database import get_db
from app.deps import get_current_user

router = APIRouter(prefix="/accounts", tags=["accounts"])


def _overview(db: Session, ledger_id: int) -> schemas.AccountsOverview:
    accts = db.query(models.Account).filter(models.Account.ledger_id == ledger_id).order_by(models.Account.id).all()
    total = sum((a.balance or Decimal(0) for a in accts if a.include_in_total), Decimal(0))
    return schemas.AccountsOverview(total=total, accounts=accts)


@router.get("", response_model=schemas.AccountsOverview)
def list_accounts(db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    return _overview(db, user.ledger_id)


@router.post("", response_model=schemas.AccountsOverview)
def create_account(
    payload: schemas.AccountCreate,
    db: Session = Depends(get_db),
    user: models.User = Depends(get_current_user),
):
    """For wallets and cards that never send a balance SMS (cash, 微信零钱, a rarely used card)."""
    if db.query(models.Account).filter(models.Account.ledger_id == user.ledger_id, models.Account.name == payload.name).first():
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "account already exists")
    db.add(models.Account(
        ledger_id=user.ledger_id, name=payload.name, kind=payload.kind, balance=payload.balance,
        balance_at=now_local() if payload.balance is not None else None, include_in_total=payload.include_in_total,
    ))
    db.commit()
    return _overview(db, user.ledger_id)


@router.put("/{account_id}", response_model=schemas.AccountsOverview)
def update_account(
    account_id: int,
    payload: schemas.AccountUpdate,
    db: Session = Depends(get_db),
    user: models.User = Depends(get_current_user),
):
    acct = db.query(models.Account).filter(models.Account.id == account_id, models.Account.ledger_id == user.ledger_id).first()
    if not acct:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "account not found")
    changes = payload.model_dump(exclude_unset=True)
    for field, value in changes.items():
        setattr(acct, field, value)
    if "balance" in changes:
        acct.balance_at = now_local()
    db.commit()
    return _overview(db, user.ledger_id)
