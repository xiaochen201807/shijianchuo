# ============================================================
# RFC 3161 国密 TSA — All-in-One 镜像
# 内含: Tongsuo(OpenSSL 国密) + fcgiwrap + nginx + chrony
#
# 说明:
#   原方案使用 GmSSL 3 的 `gmssl` CLI，但 GmSSL 3 已重写命令集，
#   不再提供 ecparam/req/x509/ts 等 OpenSSL 兼容子命令，也无法做 RFC 3161。
#   故改用 Tongsuo（支持 SM2/SM3 + openssl ts -reply），接口与脚本兼容。
# ============================================================

FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive \
    TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    PATH=/usr/local/tongsuo/bin:$PATH \
    LD_LIBRARY_PATH=/usr/local/tongsuo/lib:/usr/local/tongsuo/lib64:$LD_LIBRARY_PATH \
    OPENSSL_BIN=/usr/local/tongsuo/bin/openssl

# ----------------------------------------------------------
# 系统依赖
# ----------------------------------------------------------
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
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
        tzdata \
        procps \
        iproute2 \
        net-tools \
    && ln -fs /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && dpkg-reconfigure -f noninteractive tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && rm -f /etc/nginx/sites-enabled/default

# ----------------------------------------------------------
# 编译安装 Tongsuo (国密 OpenSSL: SM2/SM3 + RFC 3161 ts)
# ----------------------------------------------------------
RUN git clone --depth 1 https://github.com/Tongsuo-Project/Tongsuo.git /tmp/Tongsuo \
    && cd /tmp/Tongsuo \
    && ./config \
        --prefix=/usr/local/tongsuo \
        --openssldir=/usr/local/tongsuo/ssl \
        shared \
        enable-ntls \
        -Wl,-rpath,/usr/local/tongsuo/lib \
    && make -j"$(nproc)" \
    && make install_sw \
    && ldconfig \
    && rm -rf /tmp/Tongsuo \
    && /usr/local/tongsuo/bin/openssl version \
    && /usr/local/tongsuo/bin/openssl list -public-key-algorithms 2>/dev/null | head -20 || true \
    && ln -sf /usr/local/tongsuo/bin/openssl /usr/local/bin/openssl-gm \
    && ln -sf /usr/local/tongsuo/bin/openssl /usr/local/bin/gmssl

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
# 配置与脚本
# ----------------------------------------------------------
COPY tsa-server/config/tsa.cnf /etc/tsa/openssl/tsa.cnf
COPY tsa-server/config/tsa_ext.cnf /etc/tsa/openssl/tsa_ext.cnf
COPY tsa-server/scripts/generate_certs.sh /scripts/generate_certs.sh
COPY tsa-server/scripts/tsa_cgi.sh /var/www/tsa/tsa_cgi.sh

COPY docker/all-in-one/nginx.conf /etc/nginx/nginx.conf
COPY docker/all-in-one/chrony.conf /etc/chrony/chrony.conf
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

EXPOSE 80 443

HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=5 \
    CMD /scripts/healthcheck.sh

ENTRYPOINT ["/scripts/entrypoint.sh"]
