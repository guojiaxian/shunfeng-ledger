#!/bin/bash
# 重启顺风·账本云同步服务
DIR=/root/shunfeng-ledger/server
SERVICE=shunfeng-ledger

[ -f /etc/systemd/system/$SERVICE.service ] || { cp $DIR/$SERVICE.service /etc/systemd/system/ && systemctl daemon-reload; }
systemctl restart $SERVICE && echo "已重启"
systemctl status $SERVICE --no-pager | head -5
