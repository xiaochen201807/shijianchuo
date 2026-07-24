#!/bin/bash
# ============================================================
# All-in-One 入口
# ============================================================

set -e

export PATH="/usr/local/tongsuo/bin:${PATH}"
export LD_LIBRARY_PATH="/usr/local/tongsuo/lib:/usr/local/tongsuo/lib64:${LD_LIBRARY_PATH:-}"
export OPENSSL_BIN="${OPENSSL_BIN:-/usr/local/tongsuo/bin/openssl}"

echo "============================================"
echo "  RFC 3161 国密 TSA (All-in-One) 启动中"
echo "  时间: $(date)"
echo "  时区: Asia/Shanghai"
echo "============================================"

echo ""
echo "[1/5] 检查目录..."
mkdir -p \
    /etc/tsa/certs \
    /etc/nginx/tls \
    /var/www/tsa \
    /var/lib/tsa \
    /var/log/tsa \
    /var/log/nginx \
    /var/log/supervisor \
    /var/log/chrony \
    /var/lib/chrony \
    /run/fcgiwrap \
    /run/chrony \
    /run
chown -R fcgiwrap:fcgiwrap /var/www/tsa /var/lib/tsa /run/fcgiwrap 2>/dev/null || true
echo "[OK] 目录就绪"

echo ""
echo "[2/5] 检查/生成 SM2 国密证书..."
if [ -f /etc/tsa/certs/tsacert.pem ] && [ -f /etc/tsa/certs/tsakey.pem ]; then
    echo "[INFO] TSA 证书已存在，跳过生成"
else
    echo "[INFO] 开始生成 SM2 证书 (Tongsuo)..."
    /scripts/generate_certs.sh
fi
chmod 644 /etc/tsa/certs/tsacert.pem /etc/tsa/certs/cacert.pem 2>/dev/null || true
# 序列号需可写
chown -R fcgiwrap:fcgiwrap /etc/tsa/certs 2>/dev/null || true
chmod 664 /etc/tsa/certs/tsaserial 2>/dev/null || true
echo "[OK] 证书就绪"

echo ""
echo "[3/5] 检查/生成 Nginx TLS 证书..."
if [ ! -f /etc/nginx/tls/tls_cert.pem ] || [ ! -f /etc/nginx/tls/tls_key.pem ]; then
    /scripts/generate_tls_certs.sh
else
    echo "[OK] TLS 证书已存在"
fi

echo ""
echo "[4/5] 验证国密 OpenSSL (Tongsuo)..."
if [ ! -x "${OPENSSL_BIN}" ]; then
    echo "[ERROR] 未找到 ${OPENSSL_BIN}"
    exit 1
fi
"${OPENSSL_BIN}" version
# 确认 ts 子命令存在
if ! "${OPENSSL_BIN}" ts -help >/dev/null 2>&1 && ! "${OPENSSL_BIN}" ts 2>&1 | head -1 | grep -qi ts; then
    # openssl ts 无参数时通常打印 usage 并返回非 0，只要不是 command not found 即可
    if ! "${OPENSSL_BIN}" help ts >/dev/null 2>&1; then
        echo "[WARN] 无法确认 ts 子命令，继续启动（若签发失败请检查 Tongsuo 构建）"
    fi
fi
echo "[OK] 国密 OpenSSL 正常: ${OPENSSL_BIN}"

echo ""
echo "[5/5] 启动 supervisord (chronyd + fcgiwrap + nginx)..."
echo ""
echo "============================================"
echo "  服务已就绪"
echo "============================================"
echo "  HTTP:    http://0.0.0.0:80"
echo "  HTTPS:   https://0.0.0.0:443"
echo "  TSA:     POST /tsa"
echo "  Health:  GET  /health"
echo "  算法:    SM2 + SM3 (RFC 3161)"
echo "  Crypto:  Tongsuo (${OPENSSL_BIN})"
echo "  FastCGI: 127.0.0.1:9000 (内部)"
echo "============================================"
echo ""

exec /usr/bin/supervisord -n -c /etc/supervisor/supervisord.conf
