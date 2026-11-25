from typing import Optional

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app import models
from app.database import get_db
from app.deps import get_current_user

router = APIRouter(prefix="/diag", tags=["diag"])

KEEP = 400            # the newest rows kept per ledger


class LogIn(BaseModel):
    device: Optional[str] = None
    lines: list[str] = []


@router.post("/popup")
def add_popup_log(payload: LogIn, db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    for line in payload.lines[:60]:
        db.add(models.DeviceLog(ledger_id=user.ledger_id, device=(payload.device or "")[:8], kind="popup", text=line[:300]))
    db.flush()
    D = models.DeviceLog
    newest = [i for (i,) in db.query(D.id).filter(D.ledger_id == user.ledger_id).order_by(D.id.desc()).limit(KEEP)]
    if len(newest) == KEEP:
        db.query(D).filter(D.ledger_id == user.ledger_id, D.id < min(newest)).delete(synchronize_session=False)
    db.commit()
    return {"ok": True}
