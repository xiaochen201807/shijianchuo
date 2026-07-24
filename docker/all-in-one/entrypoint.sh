#!/bin/bash
# ============================================================
# All-in-One 入口:
#   1. 目录与权限
#   2. SM2 TSA/CA 证书
#   3. Nginx TLS 证书
#   4. 校验 GmSSL
#   5. supervisord 拉起 chronyd + fcgiwrap + nginx
# ============================================================

set -e

echo "============================================"
echo "  RFC 3161 国密 TSA (All-in-One) 启动中"
echo "  时间: $(date)"
echo "  时区: Asia/Shanghai"
echo "============================================"

# --- 1. 目录 ---
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

# --- 2. SM2 证书 ---
echo ""
echo "[2/5] 检查/生成 SM2 国密证书..."
if [ -f /etc/tsa/certs/tsacert.pem ] && [ -f /etc/tsa/certs/tsakey.pem ]; then
    echo "[INFO] TSA 证书已存在，跳过生成"
else
    echo "[INFO] 开始生成 SM2 证书..."
    /scripts/generate_certs.sh
fi
# 供 nginx 下载端点使用（与签名证书同目录即可，conf 已指向 /etc/tsa/certs）
chmod 644 /etc/tsa/certs/tsacert.pem /etc/tsa/certs/cacert.pem 2>/dev/null || true
echo "[OK] 证书就绪"

# --- 3. TLS ---
echo ""
echo "[3/5] 检查/生成 Nginx TLS 证书..."
if [ ! -f /etc/nginx/tls/tls_cert.pem ] || [ ! -f /etc/nginx/tls/tls_key.pem ]; then
    /scripts/generate_tls_certs.sh
else
    echo "[OK] TLS 证书已存在"
fi

# --- 4. GmSSL ---
echo ""
echo "[4/5] 验证 GmSSL3..."
gmssl version
echo "[OK] GmSSL3 正常"

# --- 5. supervisor ---
echo ""
echo "[5/5] 启动 supervisord (chronyd + fcgiwrap + nginx)..."
echo ""
echo "============================================"
echo "  服务已就绪"
echo "============================================"
echo "  HTTP:   http://0.0.0.0:80"
echo "  HTTPS:  https://0.0.0.0:443"
echo "  TSA:    POST /tsa"
echo "  Health: GET  /health"
echo "  Info:   GET  /info"
echo "  算法:   SM2 + SM3 (RFC 3161)"
echo "  FastCGI:127.0.0.1:9000 (内部)"
echo "============================================"
echo ""

exec /usr/bin/supervisord -n -c /etc/supervisor/supervisord.conf
