from datetime import timedelta
from decimal import Decimal

from fastapi import APIRouter, Depends
from sqlalchemy import func
from sqlalchemy.orm import Session

from app import models, schemas
from app.clock import now_local
from app.database import get_db
from app.deps import get_current_user

router = APIRouter(prefix="/categories", tags=["categories"])


@router.get("", response_model=list[schemas.CategoryOut])
def list_categories(
    db: Session = Depends(get_db), user: models.User = Depends(get_current_user)
):
    """Every list in the app shows categories in this order: the user's own (dragged) order when there is one,
    otherwise the biggest spenders of the last 90 days first."""
    T = models.Transaction
    since = now_local() - timedelta(days=90)
    totals = dict(
        db.query(T.category_id, func.sum(T.amount))
        .filter(T.ledger_id == user.ledger_id, T.occurred_at >= since, T.is_transfer.is_(False), T.refund_of_id.is_(None),
                T.is_refund.is_(False), T.category_id.isnot(None))
        .group_by(T.category_id).all()
    )
    out = []
    for c in db.query(models.Category).filter(models.Category.ledger_id == user.ledger_id).all():
        o = schemas.CategoryOut.model_validate(c)
        o.recent_total = Decimal(totals.get(c.id) or 0)
        out.append(o)
    # custom position first (in that order), the rest by recent amount
    out.sort(key=lambda o: (o.sort_order is None, o.sort_order if o.sort_order is not None else 0, -o.recent_total, o.id))
    return out


@router.put("/order", status_code=204)
def set_order(
    payload: schemas.CategoryOrder, db: Session = Depends(get_db), user: models.User = Depends(get_current_user)
):
    """The user dragged the categories into an order: remember it for this ledger."""
    cats = {c.id: c for c in db.query(models.Category).filter(models.Category.ledger_id == user.ledger_id, models.Category.tx_type == payload.tx_type)}
    for i, cid in enumerate(payload.ids):
        if cid in cats:
            cats[cid].sort_order = i
    db.commit()


@router.post("", response_model=schemas.CategoryOut)
def create_category(
    payload: schemas.CategoryCreate,
    db: Session = Depends(get_db),
    user: models.User = Depends(get_current_user),
):
    category = models.Category(ledger_id=user.ledger_id, **payload.model_dump())
    db.add(category)
    db.commit()
    db.refresh(category)
    return category
