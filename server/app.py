# ============================================================
# 顺风·账本 — 云端同步服务（订单 + 地点缓存 + 地点合并）
#
# 整库存储 + revision 乐观锁：
#   GET  /api/data   -> { rev, data }        data 为 null 表示云端为空
#   POST /api/data   { baseRev, data }       baseRev 与当前 rev 一致才接受，
#                                            否则 409 返回 { rev, data } 供客户端合并重推
#   GET  /api/health -> { ok: true }         无需鉴权，供探活
#
# 鉴权：除 /api/health 外均需请求头 Authorization: Bearer <token>
#       token 由环境变量 SHUNFENG_TOKEN 设置（未设置时服务拒绝启动）
# 存储：SQLite 单表，数据文件由 SHUNFENG_DB 指定（默认 ./shunfeng-ledger.db）
# ============================================================
import json
import os
import sqlite3
import threading
import time
from collections import deque
from datetime import datetime, timezone
from typing import Deque, Dict, Optional

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel

DB_PATH = os.environ.get("SHUNFENG_DB", "shunfeng-ledger.db")
TOKEN = os.environ.get("SHUNFENG_TOKEN", "")
MAX_BODY_BYTES = 20 * 1024 * 1024          # 单次推送上限 20MB（正常远小于 1MB）
RATE_LIMIT = int(os.environ.get("SHUNFENG_RATE_LIMIT", "120"))  # 每 IP 每分钟请求数

if not TOKEN:
    raise RuntimeError("请先设置环境变量 SHUNFENG_TOKEN（访问令牌），例如：export SHUNFENG_TOKEN=$(openssl rand -hex 24)")

app = FastAPI(title="shunfeng-ledger-sync", docs_url=None, redoc_url=None)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],          # WebView 内 file:// 源为 null，需放开跨域
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)

_lock = threading.Lock()
_db = sqlite3.connect(DB_PATH, check_same_thread=False)
_db.execute("PRAGMA journal_mode=WAL")
_db.execute(
    "CREATE TABLE IF NOT EXISTS ledger ("
    " id INTEGER PRIMARY KEY CHECK(id = 1),"
    " rev INTEGER NOT NULL DEFAULT 0,"
    " data TEXT,"
    " updated_at TEXT)"
)
_db.execute("INSERT OR IGNORE INTO ledger (id, rev, data, updated_at) VALUES (1, 0, NULL, NULL)")
_db.commit()


def _now() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def _get_row():
    row = _db.execute("SELECT rev, data, updated_at FROM ledger WHERE id = 1").fetchone()
    return row[0], row[1], row[2]


class PushBody(BaseModel):
    baseRev: int
    data: dict


# ---------- 简单限速：每 IP 滑动窗口 ----------
_hits: Dict[str, Deque] = {}
_rate_lock = threading.Lock()


def _check_rate(request: Request):
    ip = request.client.host if request.client else "?"
    now = time.monotonic()
    with _rate_lock:
        q = _hits.setdefault(ip, deque())
        while q and now - q[0] > 60:
            q.popleft()
        if len(q) >= RATE_LIMIT:
            raise HTTPException(status_code=429, detail="请求过于频繁")
        q.append(now)


def _check_auth(authorization: Optional[str]):
    if authorization != f"Bearer {TOKEN}":
        raise HTTPException(status_code=401, detail="令牌无效")


@app.get("/api/health")
def health():
    return {"ok": True, "time": _now()}


@app.get("/api/data")
def get_data(authorization: Optional[str] = Header(default=None), request: Request = None):
    if request is not None:
        _check_rate(request)
    _check_auth(authorization)
    with _lock:
        rev, raw, updated_at = _get_row()
    data = json.loads(raw) if raw else None
    return {"rev": rev, "data": data, "updatedAt": updated_at}


@app.post("/api/data")
def push_data(body: PushBody, authorization: Optional[str] = Header(default=None), request: Request = None):
    if request is not None:
        _check_rate(request)
    _check_auth(authorization)
    # 基本结构校验：data 至少应含 orders 数组（兼容空库首传）
    if not isinstance(body.data, dict):
        raise HTTPException(status_code=400, detail="data 必须为对象")
    if not isinstance(body.data.get("orders"), list):
        raise HTTPException(status_code=400, detail="data.orders 必须为数组")
    payload = json.dumps(body.data, ensure_ascii=False, separators=(",", ":"))
    if len(payload) > MAX_BODY_BYTES:
        raise HTTPException(status_code=413, detail="数据体积超限")
    conflict = False
    with _lock:
        rev, raw, updated_at = _get_row()
        if body.baseRev == rev:
            new_rev = rev + 1
            _db.execute(
                "UPDATE ledger SET rev = ?, data = ?, updated_at = ? WHERE id = 1",
                (new_rev, payload, _now()),
            )
            _db.commit()
        else:
            conflict = True   # 乐观锁冲突：返回最新数据供客户端三方合并后重推
    if conflict:
        return JSONResponse(status_code=409, content={"rev": rev, "data": json.loads(raw) if raw else None, "updatedAt": updated_at})
    return {"rev": new_rev}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("SHUNFENG_PORT", "8787")))
