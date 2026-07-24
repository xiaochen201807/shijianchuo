# ============================================================
# Chrony Dockerfile
# NTP 时间同步服务，确保 TSA 时间戳精确
# ============================================================

FROM alpine:3.18

# 安装 chrony 和时区数据
RUN apk add --no-cache \
    chrony \
    tzdata \
    bash \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone

# 复制 chrony 配置
COPY chrony.conf /etc/chrony/chrony.conf

# 创建运行目录
RUN mkdir -p /var/lib/chrony /var/log/chrony /run/chrony \
    && chown -R chrony:chrony /var/lib/chrony /var/log/chrony /run/chrony

# 暴露 NTP 端口
EXPOSE 123/udp

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --retries=3 --start-period=10s \
    CMD chronyc tracking || exit 1

# 启动 chrony
ENTRYPOINT ["chronyd", "-d", "-f", "/etc/chrony/chrony.conf"]
