#!/bin/bash
# 启动顺风·账本云同步服务（service 单元未安装时会自动安装）
DIR=/root/shunfeng-ledger
SERVICE=shunfeng-ledger

[ -f /etc/systemd/system/$SERVICE.service ] || { cp $DIR/$SERVICE.service /etc/systemd/system/ && systemctl daemon-reload; }
systemctl start $SERVICE && echo "已启动"
systemctl status $SERVICE --no-pager | head -5
