# ============================================================
# Nginx Dockerfile
# 反向代理 + HTTPS 终端，转发至 fcgiwrap
# ============================================================

FROM nginx:1.25-alpine

# 安装必要工具
RUN apk add --no-cache \
    bash \
    curl \
    openssl \
    tzdata \
    netcat-openbsd \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone

# 复制 nginx 配置文件
COPY nginx.conf /etc/nginx/nginx.conf

# 复制 HTTPS 证书生成脚本
COPY generate_tls_certs.sh /scripts/generate_tls_certs.sh
RUN chmod +x /scripts/generate_tls_certs.sh

# 复制入口脚本
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

# 创建日志目录；tls 为 HTTPS 证书可写目录；tsa-certs 为 SM2 证书只读挂载点
RUN mkdir -p /var/log/nginx /etc/nginx/tsa-certs /etc/nginx/tls

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD curl -sf http://localhost/health || exit 1

ENTRYPOINT ["/docker-entrypoint.sh"]
