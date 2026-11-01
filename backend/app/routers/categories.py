from datetime import timedelta
from decimal import Decimal

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import func
from sqlalchemy.orm import Session

from app import models, schemas, subcats
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


def _own_category(db: Session, user: models.User, category_id: int) -> models.Category:
    c = db.get(models.Category, category_id)
    if c is None or c.ledger_id != user.ledger_id or c.tx_type != models.TxType.expense:
        raise HTTPException(404, "no such category")
    if c.name == subcats.CAR:
        raise HTTPException(400, "the kinds of 我的车 are managed on the car page")
    return c


@router.post("/{category_id}/subs", response_model=schemas.CategoryOut)
def add_sub(category_id: int, payload: schemas.SubIn, db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    c = _own_category(db, user, category_id)
    name = payload.name.strip()
    cur = subcats.subs_of(user.ledger_id, c.name)
    if not name or len(name) > 8 or "," in name:
        raise HTTPException(400, "bad name")
    if name in cur:
        raise HTTPException(400, "already there")
    subcats.save_list(db, user.ledger, c.name, cur + [name])
    db.commit()
    return c


@router.delete("/{category_id}/subs", response_model=schemas.CategoryOut)
def delete_sub(category_id: int, name: str, db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    """Drop a sub-category: payments filed under it go back to 'no sub' (and are not guessed into it again)."""
    c = _own_category(db, user, category_id)
    cur = subcats.subs_of(user.ledger_id, c.name)
    if name not in cur:
        raise HTTPException(404, "no such sub-category")
    subcats.save_list(db, user.ledger, c.name, [x for x in cur if x != name])
    T = models.Transaction
    db.query(T).filter(T.ledger_id == user.ledger_id, T.category_id == c.id, T.sub == name).update({T.sub: None}, synchronize_session=False)
    db.query(models.MerchantRule).filter(models.MerchantRule.ledger_id == user.ledger_id, models.MerchantRule.category_id == c.id,
                                         models.MerchantRule.sub == name).update({models.MerchantRule.sub: None}, synchronize_session=False)
    db.commit()
    return c
