#!/bin/bash
# ============================================================
# docker-entrypoint.sh - Nginx 容器入口点
#
# 1. 生成 TLS 证书 (如果不存在)
# 2. 等待 TSA Server 就绪
# 3. 启动 nginx
# ============================================================

set -e

echo "============================================"
echo "  Nginx TSA 反向代理启动中..."
echo "  时间: $(date)"
echo "============================================"

# --- 1. 生成 TLS 证书 (写入独立可写目录 /etc/nginx/tls) ---
echo ""
echo "[1/3] 检查 TLS 证书..."
mkdir -p /etc/nginx/tls
if [ ! -f /etc/nginx/tls/tls_cert.pem ] || [ ! -f /etc/nginx/tls/tls_key.pem ]; then
    echo "[INFO] TLS 证书不存在，开始生成..."
    /scripts/generate_tls_certs.sh
else
    echo "[OK] TLS 证书已存在"
fi

# --- 2. 等待 TSA Server (fcgiwrap) 就绪 ---
echo ""
echo "[2/3] 等待 TSA Server (fcgiwrap) 就绪..."

MAX_RETRIES=30
RETRY_COUNT=0

while [ ${RETRY_COUNT} -lt ${MAX_RETRIES} ]; do
    # 尝试连接 tsa:9000
    if nc -z tsa 9000 2>/dev/null; then
        echo "[OK] TSA Server (fcgiwrap) 已就绪"
        break
    fi
    
    RETRY_COUNT=$((RETRY_COUNT + 1))
    echo "[INFO] 等待 TSA Server... (${RETRY_COUNT}/${MAX_RETRIES})"
    sleep 2
done

if [ ${RETRY_COUNT} -ge ${MAX_RETRIES} ]; then
    echo "[WARNING] TSA Server 未在预期时间内就绪，仍然启动 nginx"
    echo "[WARNING] 请求可能会暂时返回 502 Bad Gateway"
fi

# --- 3. 启动 nginx ---
echo ""
echo "[3/3] 启动 Nginx..."
echo ""

# 使用 nginx 官方启动命令
exec nginx -g 'daemon off;'
