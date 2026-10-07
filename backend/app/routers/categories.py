from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app import models, schemas
from app.database import get_db
from app.deps import get_current_user

router = APIRouter(prefix="/categories", tags=["categories"])


@router.get("", response_model=list[schemas.CategoryOut])
def list_categories(
    db: Session = Depends(get_db), user: models.User = Depends(get_current_user)
):
    return db.query(models.Category).filter(models.Category.ledger_id == user.ledger_id).all()


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
