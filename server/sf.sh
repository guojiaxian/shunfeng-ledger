#!/bin/bash
# 顺风·账本云同步服务管理脚本（基于 systemd）
# 用法：
#   ./sf.sh start     启动服务
#   ./sf.sh stop      停止服务
#   ./sf.sh restart   重启服务
#   ./sf.sh status    查看运行状态
#   ./sf.sh logs      实时查看日志（Ctrl+C 退出）
#   ./sf.sh enable    设置开机自启
#   ./sf.sh disable   关闭开机自启

SERVICE=shunfeng-ledger
DIR=/root/shunfeng-ledger

install_unit() {
  # service 文件未安装时自动安装（需 root）
  if [ ! -f /etc/systemd/system/$SERVICE.service ]; then
    if [ ! -f $DIR/$SERVICE.service ]; then
      echo "未找到 $DIR/$SERVICE.service"; exit 1
    fi
    cp $DIR/$SERVICE.service /etc/systemd/system/
    systemctl daemon-reload
    echo "已安装服务单元"
  fi
}

case "$1" in
  start)   install_unit; systemctl start $SERVICE && echo "已启动"; systemctl status $SERVICE --no-pager | head -5 ;;
  stop)    systemctl stop $SERVICE && echo "已停止" ;;
  restart) install_unit; systemctl restart $SERVICE && echo "已重启"; systemctl status $SERVICE --no-pager | head -5 ;;
  status)  systemctl status $SERVICE --no-pager; echo; ss -tlnp | grep :8787 || echo "注意：8787 端口未监听" ;;
  logs)    journalctl -u $SERVICE -f ;;
  enable)  install_unit; systemctl enable $SERVICE && echo "已设置开机自启" ;;
  disable) systemctl disable $SERVICE && echo "已关闭开机自启" ;;
  *) sed -n '2,13p' $0 ;;
esac
