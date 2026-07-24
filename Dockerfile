# ============================================================
# RFC 3161 国密 TSA — All-in-One 镜像
# 内含: GmSSL3 + fcgiwrap + nginx + chrony (supervisor 托管)
# 多架构: linux/amd64, linux/arm64
# ============================================================

FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive \
    TZ=Asia/Shanghai \
    LANG=C.UTF-8

# ----------------------------------------------------------
# 系统依赖: 编译工具 + nginx + chrony + fcgiwrap + supervisor
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
        nginx \
        chrony \
        supervisor \
        openssl \
        tzdata \
        procps \
        iproute2 \
        net-tools \
    && ln -fs /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && dpkg-reconfigure -f noninteractive tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && rm -f /etc/nginx/sites-enabled/default

# ----------------------------------------------------------
# 编译安装 GmSSL3
# ----------------------------------------------------------
RUN git clone --depth 1 https://github.com/guanzhi/GmSSL.git /tmp/GmSSL \
    && cd /tmp/GmSSL \
    && mkdir build && cd build \
    && cmake .. \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX=/usr/local \
    && make -j"$(nproc)" \
    && make install \
    && ldconfig \
    && rm -rf /tmp/GmSSL \
    && gmssl version

# ----------------------------------------------------------
# 目录结构
# ----------------------------------------------------------
RUN mkdir -p \
        /etc/tsa/certs \
        /etc/tsa/openssl \
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
        /scripts \
    && useradd -r -s /sbin/nologin fcgiwrap 2>/dev/null || true \
    && chown -R fcgiwrap:fcgiwrap /var/www/tsa /var/lib/tsa /run/fcgiwrap \
    && chmod -R 755 /etc/tsa

# ----------------------------------------------------------
# 配置与脚本 (构建上下文为仓库根目录)
# ----------------------------------------------------------
# TSA / GmSSL 配置与 CGI
COPY tsa-server/config/tsa.cnf /etc/tsa/openssl/tsa.cnf
COPY tsa-server/config/tsa_ext.cnf /etc/tsa/openssl/tsa_ext.cnf
COPY tsa-server/scripts/generate_certs.sh /scripts/generate_certs.sh
COPY tsa-server/scripts/tsa_cgi.sh /var/www/tsa/tsa_cgi.sh

# All-in-one 运维脚本与进程配置
COPY docker/all-in-one/nginx.conf /etc/nginx/nginx.conf
COPY docker/all-in-one/chrony.conf /etc/chrony/chrony.conf
# 作为主配置使用（避免与 conf.d 重复 section）
COPY docker/all-in-one/supervisord.conf /etc/supervisor/supervisord.conf
COPY docker/all-in-one/entrypoint.sh /scripts/entrypoint.sh
COPY docker/all-in-one/healthcheck.sh /scripts/healthcheck.sh
COPY docker/all-in-one/generate_tls_certs.sh /scripts/generate_tls_certs.sh

RUN chmod +x \
        /scripts/generate_certs.sh \
        /scripts/generate_tls_certs.sh \
        /scripts/entrypoint.sh \
        /scripts/healthcheck.sh \
        /var/www/tsa/tsa_cgi.sh

# HTTP / HTTPS (FastCGI 仅本机 127.0.0.1:9000，不对外暴露)
EXPOSE 80 443

HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=5 \
    CMD /scripts/healthcheck.sh

ENTRYPOINT ["/scripts/entrypoint.sh"]
