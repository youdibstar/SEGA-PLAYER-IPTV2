"""SEGA IPTV Player control panel + device API. Uses only authorized M3U media sources."""
import hashlib
import hmac
import os
import re
import sqlite3
from contextlib import contextmanager
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Optional

from fastapi import Depends, FastAPI, HTTPException, Request, status
from fastapi.responses import FileResponse
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from pydantic import BaseModel, Field, field_validator

BASE = Path(__file__).resolve().parent
DB_FILE = Path(os.getenv("DATABASE_PATH", str(BASE / "data" / "segaiptv.db")))
DB_FILE.parent.mkdir(parents=True, exist_ok=True)
APP = FastAPI(title="SEGA IPTV Player Control Panel", docs_url=None, redoc_url=None)
security = HTTPBasic()


@contextmanager
def database():
    con = sqlite3.connect(DB_FILE, timeout=15)
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA foreign_keys=ON")
    con.execute("PRAGMA busy_timeout=5000")
    try:
        yield con
        con.commit()
    finally:
        con.close()


def initialize():
    with database() as db:
        db.execute("PRAGMA journal_mode=WAL")
        db.executescript("""
        CREATE TABLE IF NOT EXISTS devices (
            id TEXT PRIMARY KEY,
            mac_alias TEXT NOT NULL,
            key_hash TEXT NOT NULL,
            key_tail TEXT NOT NULL,
            active INTEGER NOT NULL DEFAULT 0,
            expires_at TEXT,
            label TEXT NOT NULL DEFAULT '',
            created_at TEXT NOT NULL,
            last_seen TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS playlists (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            url TEXT NOT NULL,
            is_global INTEGER NOT NULL DEFAULT 1,
            created_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS device_playlists (
            device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
            PRIMARY KEY(device_id, playlist_id)
        );
        CREATE TABLE IF NOT EXISTS settings (
            id INTEGER PRIMARY KEY CHECK (id=1),
            app_name TEXT NOT NULL,
            accent_color TEXT NOT NULL,
            background_url TEXT NOT NULL,
            logo_url TEXT NOT NULL
        );
        INSERT OR IGNORE INTO settings VALUES
            (1, 'SEGA IPTV Player', '#E5474C', '', '');
        """)


initialize()


def admin_only(credentials: HTTPBasicCredentials = Depends(security)):
    admin = os.getenv("ADMIN_USERNAME", "")
    password = os.getenv("ADMIN_PASSWORD", "")
    # Explicit env var required; never ship a default password.
    if not admin or not password or not (
        hmac.compare_digest(credentials.username.encode(), admin.encode())
        and hmac.compare_digest(credentials.password.encode(), password.encode())
    ):
        raise HTTPException(status_code=401, detail="Invalid admin credentials", headers={"WWW-Authenticate": "Basic"})
    return True


def key_digest(key: str) -> str:
    return hashlib.sha256(key.encode("utf-8")).hexdigest()


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


class DeviceRegistration(BaseModel):
    device_id: str = Field(min_length=30, max_length=80, pattern=r"^[A-Za-z0-9-]+$")
    mac_alias: str = Field(pattern=r"^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
    device_key: str = Field(min_length=30, max_length=128)


class PlaylistInput(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    url: str = Field(min_length=8, max_length=3000)
    is_global: bool = True

    @field_validator("url")
    @classmethod
    def validate_url(cls, url: str):
        if not (url.lower().startswith("http://") or url.lower().startswith("https://")):
            raise ValueError("M3U link must use http:// or https://")
        return url.strip()


class ActivationInput(BaseModel):
    active: bool
    expires_at: Optional[str] = None
    label: str = Field(default="", max_length=100)

    @field_validator("expires_at")
    @classmethod
    def valid_expiry(cls, expires: Optional[str]):
        if expires:
            date.fromisoformat(expires)
        return expires or None


class AssignmentInput(BaseModel):
    playlist_ids: list[int]


class ThemeInput(BaseModel):
    app_name: str = Field(min_length=1, max_length=50)
    accent_color: str = Field(pattern=r"^#[a-fA-F0-9]{6}$")
    background_url: str = Field(default="", max_length=3000)
    logo_url: str = Field(default="", max_length=3000)

    @field_validator("background_url", "logo_url")
    @classmethod
    def validate_image_url(cls, val: str):
        if val and not val.startswith(("https://", "http://")):
            raise ValueError("Image URL must be http(s) or empty")
        return val


def verify_device(device_id: str, request: Request):
    key = request.headers.get("X-Device-Key", "")
    if not key:
        raise HTTPException(status_code=401, detail="Device key required")
    with database() as db:
        row = db.execute("SELECT * FROM devices WHERE id=?", (device_id,)).fetchone()
        if not row or not hmac.compare_digest(row["key_hash"], key_digest(key)):
            raise HTTPException(status_code=403, detail="Device not recognized")
        db.execute("UPDATE devices SET last_seen=? WHERE id=?", (now_iso(), device_id))
        return dict(row)


@APP.post("/api/v1/register")
def register(device: DeviceRegistration):
    with database() as db:
        existing = db.execute("SELECT key_hash FROM devices WHERE id=?", (device.device_id,)).fetchone()
        hashed = key_digest(device.device_key)
        if existing:
            if not hmac.compare_digest(existing["key_hash"], hashed):
                raise HTTPException(status_code=403, detail="Device credentials mismatch")
            db.execute("UPDATE devices SET last_seen=? WHERE id=?", (now_iso(), device.device_id))
        else:
            db.execute("""INSERT INTO devices(id,mac_alias,key_hash,key_tail,created_at,last_seen)
                          VALUES(?,?,?,?,?,?)""", (
                device.device_id, device.mac_alias, hashed, device.device_key[-4:], now_iso(), now_iso()
            ))
    return {"registered": True, "activation_required": True}


@APP.get("/api/v1/config")
def device_config(device_id: str, request: Request):
    device = verify_device(device_id, request)
    valid = not device["expires_at"] or date.fromisoformat(device["expires_at"]) >= date.today()
    enabled = bool(device["active"] and valid)
    with database() as db:
        settings = dict(db.execute("SELECT * FROM settings WHERE id=1").fetchone())
        if enabled:
            playlist_rows = db.execute("""
                SELECT DISTINCT p.id,p.name,p.url FROM playlists p
                LEFT JOIN device_playlists dp ON dp.playlist_id=p.id
                WHERE p.is_global=1 OR dp.device_id=? ORDER BY p.id DESC
            """, (device_id,)).fetchall()
        else:
            playlist_rows = []
    return {
        "active": enabled, "expires_at": device["expires_at"],
        "app_name": settings["app_name"], "accent_color": settings["accent_color"],
        "background_url": settings["background_url"], "logo_url": settings["logo_url"],
        "playlists": [dict(r) for r in playlist_rows]
    }


@APP.get("/admin")
def admin_page(_: bool = Depends(admin_only)):
    return FileResponse(BASE / "static" / "admin.html")


@APP.get("/admin/assets/{file_name}")
def admin_asset(file_name: str, _: bool = Depends(admin_only)):
    if file_name not in {"app.css", "app.js"}:
        raise HTTPException(404)
    return FileResponse(BASE / "static" / file_name)


@APP.get("/admin/api/state")
def admin_state(_: bool = Depends(admin_only)):
    with database() as db:
        devices = [dict(row) for row in db.execute("""
            SELECT id, mac_alias, key_tail, active, expires_at, label, created_at, last_seen
            FROM devices ORDER BY last_seen DESC
        """).fetchall()]
        playlists = [dict(row) for row in db.execute(
            "SELECT id,name,url,is_global,created_at FROM playlists ORDER BY id DESC").fetchall()]
        assignments = [dict(row) for row in db.execute("SELECT device_id,playlist_id FROM device_playlists")]
        settings = dict(db.execute("SELECT * FROM settings WHERE id=1").fetchone())
    return {"devices": devices, "playlists": playlists, "assignments": assignments, "settings": settings}


@APP.put("/admin/api/devices/{device_id}")
def set_activation(device_id: str, payload: ActivationInput, _: bool = Depends(admin_only)):
    with database() as db:
        cursor = db.execute("UPDATE devices SET active=?,expires_at=?,label=? WHERE id=?", (
            int(payload.active), payload.expires_at, payload.label, device_id))
        if not cursor.rowcount:
            raise HTTPException(404, "Device not found")
    return {"saved": True}


@APP.post("/admin/api/playlists")
def create_playlist(payload: PlaylistInput, _: bool = Depends(admin_only)):
    with database() as db:
        cur = db.execute("INSERT INTO playlists(name,url,is_global,created_at) VALUES(?,?,?,?)", (
            payload.name.strip(), payload.url.strip(), int(payload.is_global), now_iso()))
        ident = cur.lastrowid
    return {"id": ident}


@APP.put("/admin/api/playlists/{playlist_id}")
def edit_playlist(playlist_id: int, payload: PlaylistInput, _: bool = Depends(admin_only)):
    with database() as db:
        cursor = db.execute("UPDATE playlists SET name=?,url=?,is_global=? WHERE id=?", (
            payload.name.strip(), payload.url.strip(), int(payload.is_global), playlist_id))
        if not cursor.rowcount:
            raise HTTPException(404, "Playlist not found")
    return {"saved": True}


@APP.delete("/admin/api/playlists/{playlist_id}")
def remove_playlist(playlist_id: int, _: bool = Depends(admin_only)):
    with database() as db:
        db.execute("DELETE FROM playlists WHERE id=?", (playlist_id,))
    return {"deleted": True}


@APP.put("/admin/api/devices/{device_id}/playlists")
def set_assignments(device_id: str, payload: AssignmentInput, _: bool = Depends(admin_only)):
    with database() as db:
        if not db.execute("SELECT 1 FROM devices WHERE id=?", (device_id,)).fetchone():
            raise HTTPException(404, "Device not found")
        # Prevent invisible/invalid entries from being added to device associations.
        existing = {r[0] for r in db.execute("SELECT id FROM playlists WHERE is_global=0")}
        if not set(payload.playlist_ids).issubset(existing):
            raise HTTPException(400, "Unknown or global playlist")
        db.execute("DELETE FROM device_playlists WHERE device_id=?", (device_id,))
        db.executemany("INSERT INTO device_playlists(device_id,playlist_id) VALUES(?,?)",
                       [(device_id, p) for p in set(payload.playlist_ids)])
    return {"saved": True}


@APP.put("/admin/api/settings")
def set_theme(payload: ThemeInput, _: bool = Depends(admin_only)):
    with database() as db:
        db.execute("""UPDATE settings SET app_name=?,accent_color=?,background_url=?,logo_url=?
                      WHERE id=1""", (payload.app_name.strip(), payload.accent_color,
                                     payload.background_url.strip(), payload.logo_url.strip()))
    return {"saved": True}


@APP.get("/health")
def health():
    return {"status": "ok"}

app = APP
