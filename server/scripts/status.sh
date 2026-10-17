#!/bin/bash
# 查看顺风·账本云同步服务运行状态（含 8787 端口监听检查）
SERVICE=shunfeng-ledger

systemctl status $SERVICE --no-pager
echo
ss -tlnp | grep :8787 || echo "注意：8787 端口未监听"
