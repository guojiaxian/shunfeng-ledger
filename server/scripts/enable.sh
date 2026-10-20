#!/bin/bash
# 设置开机自启
DIR=/root/shunfeng-ledger/server
SERVICE=shunfeng-ledger

[ -f /etc/systemd/system/$SERVICE.service ] || { cp $DIR/$SERVICE.service /etc/systemd/system/ && systemctl daemon-reload; }
systemctl enable $SERVICE && echo "已设置开机自启"
