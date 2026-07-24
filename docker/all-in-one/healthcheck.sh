#!/bin/bash
# All-in-One 健康检查

set -e

# SM2 证书
if [ ! -f /etc/tsa/certs/tsacert.pem ] || [ ! -f /etc/tsa/certs/tsakey.pem ]; then
    echo "TSA certificate missing"
    exit 1
fi

# fcgiwrap
if ! pgrep -f fcgiwrap >/dev/null 2>&1; then
    echo "fcgiwrap not running"
    exit 1
fi

# nginx
if ! pgrep -x nginx >/dev/null 2>&1; then
    echo "nginx not running"
    exit 1
fi

# HTTP 探活
if ! curl -sf http://127.0.0.1/health >/dev/null; then
    echo "HTTP /health failed"
    exit 1
fi

echo "OK"
exit 0
