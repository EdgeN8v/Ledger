from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.security import OAuth2PasswordRequestForm
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app import models, schemas
from app.config import settings
from app.database import get_db
from app.default_categories import DEFAULT_CATEGORIES
from app.deps import get_current_user
from app.security import hash_password, verify_password, create_access_token

router = APIRouter(prefix="/auth", tags=["auth"])


@router.get("/me", response_model=schemas.UserOut)
def me(user: models.User = Depends(get_current_user)):
    """Exposes ledger_id so a member can share it as the "invite code" for /auth/join."""
    return user


@router.put("/ledger-settings", response_model=schemas.UserOut)
def update_ledger_settings(
    payload: schemas.LedgerSettings,
    db: Session = Depends(get_db),
    user: models.User = Depends(get_current_user),
):
    """Payday-based budget month and the holder's own names (used to spot transfers between own accounts)."""
    ledger = user.ledger
    if payload.cycle_start_day is not None:
        if not 1 <= payload.cycle_start_day <= 28:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, "cycle_start_day must be 1-28")
        ledger.cycle_start_day = payload.cycle_start_day
    if payload.owner_names is not None:
        ledger.owner_names = payload.owner_names.strip() or None
    db.commit()
    db.refresh(user)
    return user


@router.post("/register", response_model=schemas.Token)
def register(payload: schemas.UserCreate, db: Session = Depends(get_db)):
    """Creates a brand-new ledger with this user as its first member."""
    if not settings.registration_open:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "registration is closed")
    if db.query(models.User).filter(models.User.username == payload.username).first():
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "username already taken")

    ledger = models.Ledger(name=payload.ledger_name or f"{payload.display_name}的账本")
    db.add(ledger)
    db.flush()

    for name, icon, tx_type, keywords in DEFAULT_CATEGORIES:
        db.add(models.Category(ledger_id=ledger.id, name=name, icon=icon, tx_type=tx_type, keywords=keywords))

    user = models.User(
        username=payload.username,
        display_name=payload.display_name,
        password_hash=hash_password(payload.password),
        ledger_id=ledger.id,
    )
    db.add(user)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "username already taken")

    return schemas.Token(access_token=create_access_token(user.username))


@router.post("/join", response_model=schemas.Token)
def join(payload: schemas.UserJoin, db: Session = Depends(get_db)):
    """Joins an existing ledger with its random invite code."""
    if not settings.registration_open:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "registration is closed")
    if db.query(models.User).filter(models.User.username == payload.username).first():
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "username already taken")

    ledger = db.query(models.Ledger).filter(models.Ledger.invite_code == payload.invite_code.strip()).first()
    if not ledger:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "invalid invite code")

    user = models.User(
        username=payload.username,
        display_name=payload.display_name,
        password_hash=hash_password(payload.password),
        ledger_id=ledger.id,
    )
    db.add(user)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "username already taken")

    return schemas.Token(access_token=create_access_token(user.username))


@router.post("/login", response_model=schemas.Token)
def login(form: OAuth2PasswordRequestForm = Depends(), db: Session = Depends(get_db)):
    user = db.query(models.User).filter(models.User.username == form.username).first()
    if not user or not verify_password(form.password, user.password_hash):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "incorrect username or password")
    return schemas.Token(access_token=create_access_token(user.username))
