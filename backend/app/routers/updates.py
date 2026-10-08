"""Optional: hand out app builds from a folder. Off unless LEDGER_UPDATE_DIR and LEDGER_UPDATE_TOKEN are set; the URL
carries a secret token, so only whoever was given the link can download."""
import hmac
import os
import re
from pathlib import Path

from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse

router = APIRouter(prefix="/update", tags=["update"])
_NAME = re.compile(r"^[A-Za-z0-9._-]+\.(apk|json)$")


@router.get("/{token}/{name}")
def download(token: str, name: str):
    folder, secret = os.environ.get("LEDGER_UPDATE_DIR"), os.environ.get("LEDGER_UPDATE_TOKEN")
    if not folder or not secret or not hmac.compare_digest(token, secret) or not _NAME.match(name):
        raise HTTPException(404)
    path = Path(folder) / name
    if not path.is_file():
        raise HTTPException(404)
    return FileResponse(path, media_type="application/vnd.android.package-archive" if name.endswith(".apk") else "application/json", filename=name)
