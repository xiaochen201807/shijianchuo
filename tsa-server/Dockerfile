# ============================================================
# [DEPRECATED] 此文件已废弃，仅供旧版多容器架构参考
#
# 当前架构已迁移至 All-in-One 单容器模式，请使用根目录 Dockerfile。
# 根目录 Dockerfile 使用 Tongsuo (替代 GmSSL3) + nginx + fcgiwrap
# + chrony + tsa-demo (GraalVM 原生二进制)，由 supervisor 托管。
# ============================================================
#
# TSA Server Dockerfile (旧版)
# 基于 Ubuntu 22.04 构建 GmSSL3 + fcgiwrap 时间戳服务
# ============================================================

FROM ubuntu:22.04

# 设置时区和环境变量
ENV DEBIAN_FRONTEND=noninteractive
ENV TZ=Asia/Shanghai

# ----------------------------------------------------------
# 第一阶段: 安装系统依赖
# ----------------------------------------------------------
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    cmake \
    git \
    perl \
    pkg-config \
    ca-certificates \
    curl \
    fcgiwrap \
    spawn-fcgi \
    libfcgi-dev \
    openssl \
    tzdata \
    procps \
    iproute2 \
    net-tools \
    && ln -fs /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && dpkg-reconfigure -f noninteractive tzdata \
    && rm -rf /var/lib/apt/lists/*

# ----------------------------------------------------------
# 第二阶段: 编译安装 GmSSL3 (国密算法库)
# ============================================================
RUN git clone --depth 1 https://github.com/guanzhi/GmSSL.git /tmp/GmSSL \
    && cd /tmp/GmSSL \
    && mkdir build && cd build \
    && cmake .. \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX=/usr/local \
    && make -j$(nproc) \
    && make install \
    && ldconfig \
    && rm -rf /tmp/GmSSL

# 验证 GmSSL 安装
RUN gmssl version && gmssl version -a

# ----------------------------------------------------------
# 第三阶段: 创建目录结构
# ----------------------------------------------------------
RUN mkdir -p \
    /etc/tsa/certs \
    /etc/tsa/openssl \
    /var/www/tsa \
    /var/lib/tsa \
    /var/log/tsa \
    /run/fcgiwrap

# ----------------------------------------------------------
# 第四阶段: 复制配置文件和脚本
# ----------------------------------------------------------
COPY config/tsa.cnf /etc/tsa/openssl/tsa.cnf
COPY config/tsa_ext.cnf /etc/tsa/openssl/tsa_ext.cnf
COPY scripts/generate_certs.sh /scripts/generate_certs.sh
COPY scripts/tsa_cgi.sh /var/www/tsa/tsa_cgi.sh
COPY scripts/entrypoint.sh /scripts/entrypoint.sh
COPY scripts/healthcheck.sh /scripts/healthcheck.sh

# 设置脚本执行权限
RUN chmod +x \
    /scripts/generate_certs.sh \
    /var/www/tsa/tsa_cgi.sh \
    /scripts/entrypoint.sh \
    /scripts/healthcheck.sh

# 创建 fcgiwrap 运行用户
RUN useradd -r -s /sbin/nologin fcgiwrap 2>/dev/null || true

# 设置目录权限
RUN chown -R fcgiwrap:fcgiwrap /var/www/tsa /var/lib/tsa /run/fcgiwrap \
    && chmod -R 755 /etc/tsa

# 暴露 fcgiwrap 端口 (FastCGI)
EXPOSE 9000

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=5 \
    CMD /scripts/healthcheck.sh

# 入口点
ENTRYPOINT ["/scripts/entrypoint.sh"]
