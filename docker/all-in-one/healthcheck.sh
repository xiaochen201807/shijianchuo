#!/bin/bash
# 最终镜像健康检查: tsa-server-java + nginx + 原生 Demo

set -e

if [ ! -f /etc/tsa/certs/tsacert.pem ] || [ ! -f /etc/tsa/certs/tsakey.pem ]; then
    echo "TSA certificate missing"
    exit 1
fi

if ! pgrep -f tsa-server-java >/dev/null 2>&1; then
    echo "tsa-server-java not running"
    exit 1
fi

if ! pgrep -x nginx >/dev/null 2>&1; then
    echo "nginx not running"
    exit 1
fi

# 原生 Demo 进程 (二进制名 tsa-demo)
if ! pgrep -f '/usr/local/bin/tsa-demo|tsa-demo' >/dev/null 2>&1; then
    echo "tsa-demo native binary not running"
    exit 1
fi

if ! curl -sf http://127.0.0.1/health >/dev/null; then
    echo "HTTP /health failed"
    exit 1
fi

# Demo API (经 nginx 反代 或 直连 9090)
if ! curl -sf "http://127.0.0.1/api/sm3/hash?text=ok" >/dev/null \
   && ! curl -sf "http://127.0.0.1:9090/api/sm3/hash?text=ok" >/dev/null; then
    echo "tsa-demo /api health failed"
    exit 1
fi

echo "OK"
exit 0
