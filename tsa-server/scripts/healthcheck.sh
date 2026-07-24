#!/bin/bash
# ============================================================
# healthcheck.sh - TSA Server 健康检查脚本
# ============================================================

# 检查证书文件是否存在
if [ ! -f /etc/tsa/certs/tsacert.pem ]; then
    echo "TSA certificate not found"
    exit 1
fi

# 检查私钥文件是否存在
if [ ! -f /etc/tsa/certs/tsakey.pem ]; then
    echo "TSA private key not found"
    exit 1
fi

# 检查 fcgiwrap 进程是否在运行
if ! pgrep -f fcgiwrap > /dev/null 2>&1; then
    echo "fcgiwrap process not running"
    exit 1
fi

# 检查端口 9000 是否在监听
if ! ss -tlnp | grep -q ":9000" 2>/dev/null; then
    if ! netstat -tlnp 2>/dev/null | grep -q ":9000"; then
        echo "Port 9000 not listening"
        exit 1
    fi
fi

echo "OK"
exit 0
