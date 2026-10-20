# ============================================================
# 顺风·账本 — 云端同步服务（订单 + 地点缓存 + 地点合并）
#
# 同步协议（与客户端约定，勿改对外结构）：
#   GET  /api/data   -> { rev, data }        data 为 null 表示云端为空
#   POST /api/data   { baseRev, data }       baseRev 与当前 rev 一致才接受，
#                                            否则 409 返回 { rev, data } 供客户端合并重推
#   GET  /api/health -> { ok: true }         无需鉴权，供探活
#
# 鉴权：除 /api/health 外均需请求头 Authorization: Bearer <token>
#       token 由环境变量 SHUNFENG_TOKEN 设置（未设置时服务拒绝启动）
#
# 存储：SQLite 多行表，便于管理员直接 SQL 查询：
#   meta(id=1)            rev / updated_at
#   orders                每单一行（id/platform/date/amount/.../extra）
#   place_cache           每地点一行（坐标 + manual/ignored 标注 + extra）
#   place_merges          别名 -> 主名称，一行一条
# 未知字段统一落入 extra JSON 列，保证读写往返无损。
# 首次启动若发现旧版单行 ledger 表，自动拆行迁移并改名为 ledger_legacy。
# 数据文件由 SHUNFENG_DB 指定（默认 ./shunfeng-ledger.db）
# ============================================================
import json
import os
import sqlite3
import threading
import time
from collections import deque
from datetime import datetime, timezone
from typing import Deque, Dict, List, Optional, Tuple

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

# ---------- 表结构（列顺序与 _ORDER_COLS/_PC_COLS 一一对应） ----------
# 客户端 JSON 键 -> 数据库列名（其余为同名；未列出的键进 extra）
_ORDER_KEYMAP = {"id": "id", "platform": "platform", "date": "date", "time": "time",
                 "amount": "amount", "bonus": "bonus", "distance": "distance",
                 "duration": "duration", "seats": "seats", "rideType": "ride_type",
                 "pickup": "pickup", "dropoff": "dropoff", "note": "note",
                 "createdAt": "created_at", "updatedAt": "updated_at"}
_ORDER_COLS = ("id", "platform", "date", "time", "amount", "bonus", "distance",
               "duration", "seats", "ride_type", "pickup", "dropoff", "note",
               "created_at", "updated_at", "extra")
_PC_COLS = ("name", "lng", "lat", "district", "city", "resolved", "manual", "ignored", "extra")

_db.executescript("""
CREATE TABLE IF NOT EXISTS meta (
  id INTEGER PRIMARY KEY CHECK(id = 1),
  rev INTEGER NOT NULL DEFAULT 0,
  updated_at TEXT);
INSERT OR IGNORE INTO meta (id, rev, updated_at) VALUES (1, 0, NULL);

CREATE TABLE IF NOT EXISTS orders (
  id TEXT PRIMARY KEY,
  platform TEXT, date TEXT, time TEXT,
  amount REAL, bonus REAL, distance REAL, duration INTEGER,
  seats TEXT, ride_type TEXT,
  pickup TEXT, dropoff TEXT, note TEXT,
  created_at INTEGER, updated_at INTEGER,
  extra TEXT);

CREATE TABLE IF NOT EXISTS place_cache (
  name TEXT PRIMARY KEY,
  lng REAL, lat REAL, district TEXT, city TEXT,
  resolved TEXT, manual INTEGER, ignored INTEGER,
  extra TEXT);

CREATE TABLE IF NOT EXISTS place_merges (
  alias TEXT PRIMARY KEY,
  root TEXT NOT NULL);
""")


def _now() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


# ---------- 旧版单行库自动迁移：ledger(rev,data) -> 拆行 ----------
def _migrate_legacy():
    legacy = _db.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='ledger'").fetchone()
    if not legacy:
        return
    row = _db.execute("SELECT rev, data FROM ledger WHERE id = 1").fetchone()
    _db.execute("ALTER TABLE ledger RENAME TO ledger_legacy")
    if not row or row[1] is None:
        _db.commit()
        return
    rev = int(row[0])
    data = json.loads(row[1]) if isinstance(row[1], str) else row[1]
    if not isinstance(data, dict):
        _db.commit()
        return
    orders = data.get("orders") or []
    pc = data.get("placeCache") or {}
    pm = data.get("placeMerges") or {}
    _write_rows(rev, orders, pc, pm)
    _db.commit()


def _cols_from(obj: dict, keymap: Dict[str, str], identity: str) -> list:
    """对象 -> 行值：按 keymap 取已知列；其余键原样落入 extra JSON（往返无损）。"""
    extra = {}
    for k, v in obj.items():
        if k in keymap and isinstance(v, (list, dict)):
            extra[k] = v   # 复合类型无法直接进列，落 extra JSON 保持无损
        elif k not in keymap and k != identity:
            extra[k] = v
    rev_map = {v: k for k, v in keymap.items()}   # 列名 -> 客户端键
    col_keys = [c for c in rev_map if c != keymap.get(identity)]
    vals = []
    for c in col_keys:
        v = obj.get(rev_map[c])
        vals.append(None if isinstance(v, (list, dict)) else v)  # 复合类型已落 extra
    vals.append(json.dumps(extra, ensure_ascii=False) if extra else None)
    return vals


def _write_rows(rev: int, orders: List[dict], pc: Dict[str, dict], pm: Dict[str, str]):
    _db.execute("DELETE FROM orders")
    _db.execute("DELETE FROM place_cache")
    _db.execute("DELETE FROM place_merges")
    ph = ",".join("?" * len(_ORDER_COLS))
    for o in orders:
        if not isinstance(o, dict) or not o.get("id"):
            continue
        _db.execute(f"INSERT OR REPLACE INTO orders VALUES ({ph})",
                    [o.get("id")] + _cols_from(o, _ORDER_KEYMAP, "id"))
    for name, p in pc.items():
        if not isinstance(p, dict):
            continue
        keymap = {"lng": "lng", "lat": "lat", "district": "district", "city": "city",
                  "resolved": "resolved", "manual": "manual", "ignored": "ignored"}
        _db.execute(f"INSERT OR REPLACE INTO place_cache VALUES ({','.join('?' * len(_PC_COLS))})",
                    [name] + _cols_from(p, keymap, "name"))
    for alias, root in pm.items():
        if isinstance(root, str):
            _db.execute("INSERT OR REPLACE INTO place_merges VALUES (?, ?)", (alias, root))
    _db.execute("UPDATE meta SET rev = ?, updated_at = ? WHERE id = 1", (rev, _now()))


_migrate_legacy()
_db.commit()


# ---------- 行 -> 对象重组（读写往返保真） ----------
def _row_to_obj(row: tuple, cols, int_as_bool) -> dict:
    obj = {}
    extra = row[-1]
    for i, c in enumerate(cols[:-1]):
        v = row[i]
        if v is None and c not in ("id", "name"):
            continue          # 未写入的字段不出现在 JSON，保持与客户端原始结构一致
        if c in int_as_bool and v is not None:
            v = bool(v)
        if isinstance(v, float) and v.is_integer():
            v = int(v)   # JS 数字序列化无 .0，REAL 列读回整数须还原，保证往返无损
        obj[c] = v
    if extra:
        obj.update(json.loads(extra))
    return obj


def _read_all() -> Tuple[int, Optional[dict], Optional[str]]:
    rev, updated_at = _db.execute("SELECT rev, updated_at FROM meta WHERE id = 1").fetchone()
    if rev == 0:
        return 0, None, updated_at
    orders = []
    for row in _db.execute("SELECT * FROM orders ORDER BY rowid"):
        obj = _row_to_obj(row, _ORDER_COLS, set())
        # 客户端字段名为驼峰：ride_type -> rideType, created_at -> createdAt ...
        obj = {("rideType" if k == "ride_type" else
                "createdAt" if k == "created_at" else
                "updatedAt" if k == "updated_at" else k): v for k, v in obj.items()}
        orders.append(obj)
    pc = {}
    for row in _db.execute("SELECT * FROM place_cache ORDER BY rowid"):
        obj = _row_to_obj(row, _PC_COLS, {"manual", "ignored"})
        obj.pop("name", None)          # name 已作为字典键，不重复保留
        pc[row[0]] = obj
    pm = {a: r for a, r in _db.execute("SELECT alias, root FROM place_merges ORDER BY rowid")}
    return rev, {"orders": orders, "placeCache": pc, "placeMerges": pm}, updated_at


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
        rev, data, updated_at = _read_all()
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
    rev, data, updated_at = 0, None, None
    with _lock:
        cur_rev = _db.execute("SELECT rev FROM meta WHERE id = 1").fetchone()[0]
        if body.baseRev == cur_rev:
            orders = [o for o in body.data.get("orders", []) if isinstance(o, dict)]
            pc = body.data.get("placeCache") or {}
            pm = body.data.get("placeMerges") or {}
            if not isinstance(pc, dict) or not isinstance(pm, dict):
                raise HTTPException(status_code=400, detail="placeCache/placeMerges 必须为对象")
            try:
                _write_rows(cur_rev + 1, orders, pc, pm)
                _db.commit()
            except Exception:
                _db.rollback()
                raise HTTPException(status_code=400, detail="数据写入失败，字段类型不合法")
        else:
            conflict = True   # 乐观锁冲突：返回最新数据供客户端三方合并后重推
            rev, data, updated_at = _read_all()
    if conflict:
        return JSONResponse(status_code=409, content={"rev": rev, "data": data, "updatedAt": updated_at})
    return {"rev": cur_rev + 1}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("SHUNFENG_PORT", "8787")))
