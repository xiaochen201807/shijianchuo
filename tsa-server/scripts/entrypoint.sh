#!/bin/bash
# ============================================================
# entrypoint.sh - TSA Server 容器入口点
#
# 执行顺序:
#   1. 生成 SM2 国密证书 (如果不存在)
#   2. 启动 fcgiwrap (FastCGI 服务)
#   3. 等待并监控
# ============================================================

set -e

echo "============================================"
echo "  TSA Server 启动中..."
echo "  时间: $(date)"
echo "  时区: $(cat /etc/timezone 2>/dev/null || echo 'Unknown')"
echo "============================================"

# --- 1. 确保目录存在 ---
echo ""
echo "[1/4] 检查目录结构..."
mkdir -p /etc/tsa/certs /var/www/tsa /var/lib/tsa /var/log/tsa /run/fcgiwrap
echo "[OK] 目录就绪"

# --- 2. 生成证书 ---
echo ""
echo "[2/4] 检查/生成 SM2 国密证书..."
if [ -f /etc/tsa/certs/tsacert.pem ] && [ -f /etc/tsa/certs/tsakey.pem ]; then
    echo "[INFO] TSA 证书已存在，跳过生成"
else
    echo "[INFO] 证书不存在，开始生成..."
    /scripts/generate_certs.sh
fi
echo "[OK] 证书就绪"

# --- 3. 验证 GmSSL ---
echo ""
echo "[3/4] 验证 GmSSL3 安装..."
gmssl version
echo "[OK] GmSSL3 验证通过"

# --- 4. 启动 fcgiwrap ---
echo ""
echo "[4/4] 启动 fcgiwrap (FastCGI)..."

# 停止可能已存在的 fcgiwrap 进程
pkill -f fcgiwrap 2>/dev/null || true
sleep 1

# 使用 spawn-fcgi 启动 fcgiwrap
# 必须监听 0.0.0.0，否则其他容器无法通过 Docker 网络访问 9000
# -n: 前台不 daemonize 由我们后台托管；此处用 spawn-fcgi 自身后台化
spawn-fcgi \
    -a 0.0.0.0 \
    -p 9000 \
    -u fcgiwrap \
    -g fcgiwrap \
    -P /var/run/fcgiwrap.pid \
    -n \
    -- /usr/sbin/fcgiwrap &

FCGI_PID=$!
echo "[OK] fcgiwrap 已启动 (PID: ${FCGI_PID}, 端口: 0.0.0.0:9000)"

# --- 等待 fcgiwrap 就绪 ---
sleep 2

# 检查 fcgiwrap 是否在运行 (spawn-fcgi -n 时 FCGI_PID 为 fcgiwrap 本身)
if ! pgrep -f fcgiwrap > /dev/null 2>&1; then
    echo "[ERROR] fcgiwrap 启动失败!"
    exit 1
fi

echo ""
echo "============================================"
echo "  TSA Server 启动完成!"
echo "============================================"
echo ""
echo "  FastCGI 监听: 0.0.0.0:9000"
echo "  CGI 脚本:    /var/www/tsa/tsa_cgi.sh"
echo "  TSA 证书:    /etc/tsa/certs/tsacert.pem"
echo "  TSA 私钥:    /etc/tsa/certs/tsakey.pem"
echo "  CA 证书:     /etc/tsa/certs/cacert.pem"
echo "  签名算法:    SM2 + SM3"
echo "  配置文件:    /etc/tsa/openssl/tsa.cnf"
echo ""
echo "  等待 nginx 反向代理连接..."
echo ""

# --- 主进程: 保持运行并监控 fcgiwrap ---
while true; do
    if ! pgrep -f fcgiwrap > /dev/null 2>&1; then
        echo "[$(date)] [ERROR] fcgiwrap 进程退出，尝试重启..."

        spawn-fcgi \
            -a 0.0.0.0 \
            -p 9000 \
            -u fcgiwrap \
            -g fcgiwrap \
            -P /var/run/fcgiwrap.pid \
            -n \
            -- /usr/sbin/fcgiwrap &

        FCGI_PID=$!
        echo "[$(date)] [INFO] fcgiwrap 已重启 (PID: ${FCGI_PID})"
    fi
    sleep 5
done
