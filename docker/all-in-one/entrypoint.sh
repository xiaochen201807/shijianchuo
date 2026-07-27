#!/bin/bash
# ============================================================
# All-in-One 入口: 证书 → 校验 → supervisord
# 进程: chronyd + fcgiwrap + nginx + tsa-demo(原生二进制)
# ============================================================

set -e

# shellcheck source=/dev/null
if [ -f /scripts/openssl-env.sh ]; then
    source /scripts/openssl-env.sh
else
    export PATH="/usr/local/tongsuo/bin:${PATH}"
    export LD_LIBRARY_PATH="/usr/local/tongsuo/lib:/usr/local/tongsuo/lib64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
    export OPENSSL_BIN="${OPENSSL_BIN:-/usr/local/tongsuo/bin/openssl}"
    mkdir -p /usr/local/tongsuo/ssl
    if [ ! -s /usr/local/tongsuo/ssl/openssl.cnf ] && [ -s /etc/tsa/openssl/openssl-runtime.cnf ]; then
        cp -f /etc/tsa/openssl/openssl-runtime.cnf /usr/local/tongsuo/ssl/openssl.cnf
    fi
    export OPENSSL_CONF=/usr/local/tongsuo/ssl/openssl.cnf
fi

echo "============================================"
echo "  RFC 3161 国密 TSA 最终镜像启动"
echo "  时间: $(date)"
echo "============================================"

echo ""
echo "[1/6] 目录..."
mkdir -p \
    /etc/tsa/certs /etc/nginx/tls /var/www/tsa /var/lib/tsa \
    /var/log/tsa /var/log/nginx /var/log/supervisor /var/log/chrony \
    /var/lib/chrony /run/fcgiwrap /run/chrony /run /opt/tsa-demo/config
chown -R fcgiwrap:fcgiwrap /var/www/tsa /var/lib/tsa /var/log/tsa /run/fcgiwrap 2>/dev/null || true
echo "[OK]"

echo ""
echo "[2/6] SM2 证书..."
if [ -f /etc/tsa/certs/tsacert.pem ] && [ -f /etc/tsa/certs/tsakey.pem ]; then
    echo "[INFO] 证书已存在"
else
    /scripts/generate_certs.sh
fi
chmod 644 /etc/tsa/certs/tsacert.pem /etc/tsa/certs/cacert.pem 2>/dev/null || true
chown -R fcgiwrap:fcgiwrap /etc/tsa/certs 2>/dev/null || true
chmod 664 /etc/tsa/certs/tsaserial 2>/dev/null || true
echo "[OK]"

echo ""
echo "[3/6] TLS 证书..."
if [ ! -f /etc/nginx/tls/tls_cert.pem ] || [ ! -f /etc/nginx/tls/tls_key.pem ]; then
    if ! /scripts/generate_tls_certs.sh; then
        echo "[ERROR] Nginx TLS 证书生成失败，容器退出"
        exit 1
    fi
else
    echo "[OK] TLS 证书已存在"
fi

echo ""
echo "[4/6] Tongsuo..."
test -x "${OPENSSL_BIN}"
"${OPENSSL_BIN}" version
echo "[OK]"

echo ""
echo "[5/6] 原生 Demo 二进制..."
if [ ! -x /usr/local/bin/tsa-demo ]; then
    echo "[ERROR] /usr/local/bin/tsa-demo 不存在或不可执行"
    exit 1
fi
# 无 JVM 探测
if command -v java >/dev/null 2>&1; then
    echo "[WARN] 镜像内意外发现 java，但 Demo 仍使用原生二进制"
else
    echo "[OK] 镜像内无 JVM (符合预期)"
fi
ls -lh /usr/local/bin/tsa-demo
echo "[OK] tsa-demo 原生二进制就绪"

echo ""
echo "[6/6] supervisord..."
echo ""
echo "============================================"
echo "  已就绪 (单镜像 / 无 JVM)"
echo "============================================"
echo "  TSA:      POST http://0.0.0.0:80/tsa"
echo "  Demo API: http://0.0.0.0:80/api/...  (nginx→原生二进制)"
echo "  Demo 直连:http://0.0.0.0:9090/api/..."
echo "  Health:   GET  /health"
echo "  Info:     GET  /info"
echo "  二进制:   /usr/local/bin/tsa-demo"
echo "  进程:     chronyd + fcgiwrap + nginx + tsa-demo"
echo "============================================"
echo ""

exec /usr/bin/supervisord -n -c /etc/supervisor/supervisord.conf
