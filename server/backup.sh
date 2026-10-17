#!/bin/sh
# 每日数据快照备份：从 SQLite 导出 JSON，保留最近 7 份。
# crontab 示例（每天 3:30 执行）：
#   30 3 * * * /root/shunfeng-ledger/backup.sh >> /root/shunfeng-ledger/backup.log 2>&1
set -e
DIR=/root/shunfeng-ledger
DB="$DIR/shunfeng-ledger.db"
OUT="$DIR/backups"
mkdir -p "$OUT"

python3 - "$DB" "$OUT" <<'PY'
import json, sqlite3, sys, datetime
db_path, out_dir = sys.argv[1], sys.argv[2]
con = sqlite3.connect(db_path)
row = con.execute("SELECT rev, data, updated_at FROM ledger WHERE id = 1").fetchone()
payload = {
    "type": "shunfeng-ledger-server-snapshot",
    "rev": row[0],
    "exportedAt": datetime.datetime.now().isoformat(timespec="seconds"),
    "data": json.loads(row[1]) if row[1] else None,
}
name = f"{out_dir}/snapshot-{datetime.date.today().isoformat()}.json"
with open(name, "w", encoding="utf-8") as f:
    json.dump(payload, f, ensure_ascii=False, indent=2)
print("saved:", name, "rev:", row[0])
PY

# 只保留最近 7 份
ls -1t "$OUT"/snapshot-*.json 2>/dev/null | tail -n +8 | xargs -r rm -f
