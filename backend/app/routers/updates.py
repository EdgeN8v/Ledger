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


# --- for the app itself: signed-in users ask "is there a newer build?" and download it. No secret link is baked into the app.
from app.deps import get_current_user  # noqa: E402
from fastapi import Depends  # noqa: E402
import json  # noqa: E402

app_router = APIRouter(prefix="/app-update", tags=["update"])


def _latest():
    folder = os.environ.get("LEDGER_UPDATE_DIR")
    path = Path(folder) / "version.json" if folder else None
    if not path or not path.is_file():
        raise HTTPException(404)
    info = json.loads(path.read_text(encoding="utf-8"))
    if not _NAME.match(str(info.get("apk", ""))) or not (Path(folder) / info["apk"]).is_file():
        raise HTTPException(404)
    return folder, info


@app_router.get("/latest")
def latest(user=Depends(get_current_user)):
    return _latest()[1]


@app_router.get("/apk")
def latest_apk(user=Depends(get_current_user)):
    folder, info = _latest()
    return FileResponse(Path(folder) / info["apk"], media_type="application/vnd.android.package-archive", filename=info["apk"])
