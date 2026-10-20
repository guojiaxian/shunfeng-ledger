# 顺风·账本 云端同步服务 — 阿里云部署指南

在阿里云轻量服务器（2C2G，公网 IP）上部署本目录的 FastAPI 服务，供 App 实时同步订单与地点数据。

## 0. 前置要求

- 阿里云轻量应用服务器，能 SSH 登录（root 或 sudo 用户）
- 已安装 Python 3.8+（CentOS/Ubuntu 一般自带 `python3`，执行 `python3 --version` 确认）
- 在阿里云控制台「防火墙 / 安全组」放行 TCP 端口 **8787**

## 1. 上传文件

把本目录的 `app.py`、`requirements.txt`、`backup.sh` 上传到服务器 `/root/shunfeng-ledger/`：

```bash
# 本地执行（或在服务器上用 vim 粘贴内容亦可）
scp app.py requirements.txt backup.sh root@你的公网IP:/root/shunfeng-ledger/
```

## 2. 安装依赖并试运行

```bash
ssh root@你的公网IP
cd /root/shunfeng-ledger
python3 -m venv venv
venv/bin/pip install -r requirements.txt

# 生成一个访问令牌（妥善保存，App 设置里要填）
openssl rand -hex 24

# 前台试运行（把 <令牌> 换成上一步输出）
SHUNFENG_TOKEN=<令牌> SHUNFENG_PORT=8787 venv/bin/uvicorn app:app --host 0.0.0.0 --port 8787
```

另开一个终端验证：

```bash
curl http://127.0.0.1:8787/api/health
# 期望：{"ok":true,...}

curl http://127.0.0.1:8787/api/data
# 期望：{"detail":"令牌无效"}（401，说明鉴权生效）
```

确认无误后 Ctrl+C 停止，转正式部署。

## 3. systemd 常驻

编辑本目录的 `shunfeng-ledger.service`，把 `Environment=SHUNFENG_TOKEN=your-token-here` 里的
`your-token-here` 换成第 2 步生成的令牌，然后：

```bash
scp shunfeng-ledger.service root@你的公网IP:/etc/systemd/system/
systemctl daemon-reload
systemctl enable --now shunfeng-ledger
systemctl status shunfeng-ledger     # 应显示 active (running)
```

日志查看：`journalctl -u shunfeng-ledger -f`

## 4. 每日自动备份（推荐）

```bash
chmod +x /root/shunfeng-ledger/backup.sh
crontab -e
# 加入一行（每天 03:30 快照，保留最近 7 份）：
30 3 * * * /root/shunfeng-ledger/backup.sh >> /root/shunfeng-ledger/backup.log 2>&1
```

备份输出在 `/root/shunfeng-ledger/backups/snapshot-日期.json`，可与 App 内「导出数据到文件」互为双保险。

## 5. App 侧配置

打开顺风账本 → 右上角设置 →「☁️ 云端同步」：

- 服务器地址：`http://你的公网IP:8787`
- 访问令牌：第 2 步生成的令牌
- 点「保存并同步」

首次同步：云端为空时会把本机数据整体上传；云端已有数据（例如先在另一台设备启用过）会自动合并。

> 建议升级后先在**主力手机**上完成首次同步（整体上传 + 权威对齐），再在第二台设备上登录同步。

## 6. 常用运维

也可以用管理脚本（已随服务端文件一起上传）：

```bash
chmod +x /root/shunfeng-ledger/sf.sh
/root/shunfeng-ledger/sf.sh start | stop | restart | status | logs | enable | disable
```

或直接用 systemctl：

```bash
systemctl restart shunfeng-ledger        # 重启
systemctl stop shunfeng-ledger           # 停止
ls -lt /root/shunfeng-ledger/backups/    # 查看备份
```

## 6.1 数据库结构（多行表，可直接 SQL 查询）

```bash
sqlite3 /root/shunfeng-ledger/shunfeng-ledger.db
```

| 表 | 内容 | 关键列 |
|---|---|---|
| `meta` | 全局版本号（单行） | `rev`（乐观锁版本，每次推送 +1）、`updated_at` |
| `orders` | 订单，每单一行 | `id`(主键)、`platform`、`date`、`time`、`amount`、`bonus`、`distance`、`duration`、`pickup`、`dropoff`、`note`、`created_at`、`updated_at`、`extra` |
| `place_cache` | 地点坐标缓存，每地点一行 | `name`(主键)、`lng`、`lat`、`district`、`city`、`resolved`(完整地址)、`manual`、`ignored`、`extra` |
| `place_merges` | 地点合并关系 | `alias`(别名主键) → `root`(主名称) |

客户端 JSON 的未知字段统一存入 `extra` JSON 列，读写往返无损。旧版单行 `ledger` 表首次启动自动拆行迁移，原表保留为 `ledger_legacy`。

常用管理查询：

```sql
SELECT date, platform, amount, pickup, dropoff FROM orders ORDER BY date DESC LIMIT 20;  -- 最近订单
SELECT COUNT(*), SUM(amount) FROM orders;                                                -- 总单数/总金额
SELECT name, manual, ignored FROM place_cache WHERE manual = 1;                          -- 人工标注的地点
SELECT * FROM place_merges;                                                              -- 合并关系
SELECT rev, updated_at FROM meta;                                                        -- 当前版本
```

## 7. 安全说明

- 当前走 HTTP + Bearer Token：令牌防「他人写入/读取接口」，但传输不加密。订单数据经公网明文传输有一定风险。
- 后续加固（无需改代码，App 里把地址换成 https 即可）：
  1. 绑定域名 + Let's Encrypt 免费证书，前面加 nginx/Caddy 反代 8787；
  2. 或改用自签证书 + App 端证书锁定。
- 服务端只暴露 `/api/health`、`/api/data` 三个接口，无其他攻击面；令牌泄露时在服务器改 `SHUNFENG_TOKEN` 后重启即可作废旧令牌。
