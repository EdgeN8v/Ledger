"""Statement uploads from the app: the phone hands over the file as it is (a password-protected zip) and the server does
the unzipping and matching, so nothing has to be opened on the phone."""
import base64
import zipfile

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app import jdbill, models
from app.database import get_db
from app.deps import get_current_user

router = APIRouter(prefix="/import", tags=["import"])


class JdIn(BaseModel):
    zip_b64: str
    password: str = ""
    dry_run: bool = False


@router.post("/jd")
def import_jd(body: JdIn, db: Session = Depends(get_db), user: models.User = Depends(get_current_user)):
    try:
        data = base64.b64decode(body.zip_b64, validate=False)
        text = jdbill.read_zip(data, body.password)
        rows = jdbill.parse(text)
    except (RuntimeError, zipfile.BadZipFile) as e:
        raise HTTPException(400, "打不开这个文件: 密码不对, 或者不是京东的账单压缩包") from e
    except ValueError as e:
        raise HTTPException(400, "这不是京东的交易流水账单") from e
    matched, missing = jdbill.analyze(db, user.ledger_id, rows)
    stats = None if body.dry_run else jdbill.apply(db, user.ledger_id, matched)
    return jdbill.summary(matched, missing, stats)
