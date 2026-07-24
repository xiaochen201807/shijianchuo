# ============================================================
# RFC 3161 国密 TSA — 最终 All-in-One 镜像 (唯一发布镜像)
#
# 多阶段构建:
#   1) GraalVM Native Image  →  tsa-demo 原生二进制 (无 JVM)
#   2) Ubuntu 运行时         →  Tongsuo + nginx + fcgiwrap + chrony
#                              + 拷贝 tsa-demo，由 supervisor 托管
#
# 对外:
#   :80/:443  RFC 3161 /tsa  +  /api/* (反代到原生 Demo)
#   :9090     Demo REST 直连
#
# 镜像: ghcr.io/<owner>/<repo>/tsa:latest
# ============================================================

# ------------------------------------------------------------
# 阶段 1: 编译 tsa-demo 原生二进制 (无 JVM)
# ------------------------------------------------------------
FROM ghcr.io/graalvm/native-image-community:21-ol9 AS native-demo

WORKDIR /src

# Maven (OL9 / microdnf)
RUN (microdnf install -y maven gzip tar findutils \
      && microdnf clean all) \
    || (curl -fsSL https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz \
          | tar -xz -C /opt \
        && ln -sf /opt/apache-maven-3.9.9/bin/mvn /usr/local/bin/mvn)

ENV MAVEN_OPTS="-Xmx4g"

COPY pom.xml ./
COPY sdk ./sdk
COPY sdk-demo ./sdk-demo

RUN mvn -B -f pom.xml -pl sdk -am clean install -DskipTests \
    && mvn -B -f pom.xml -pl sdk-demo -am -Pnative -DskipTests package \
    && test -x sdk-demo/target/tsa-demo \
    && ls -lh sdk-demo/target/tsa-demo \
    && file sdk-demo/target/tsa-demo || true

# ------------------------------------------------------------
# 阶段 2: 运行时 (Tongsuo TSA + 原生 Demo)
# ------------------------------------------------------------
FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive \
    TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    PATH=/usr/local/tongsuo/bin:/usr/local/bin:$PATH \
    LD_LIBRARY_PATH=/usr/local/tongsuo/lib:/usr/local/tongsuo/lib64 \
    OPENSSL_BIN=/usr/local/tongsuo/bin/openssl \
    TSA_URL=http://127.0.0.1/tsa \
    SERVER_PORT=9090

# 系统依赖 (无 JRE/JDK)
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
        zlib1g \
    && ln -fs /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && dpkg-reconfigure -f noninteractive tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && rm -f /etc/nginx/sites-enabled/default

# Tongsuo: SM2/SM3 + openssl ts (RFC 3161)
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
    && echo /usr/local/tongsuo/lib > /etc/ld.so.conf.d/tongsuo.conf \
    && ldconfig \
    && rm -rf /tmp/Tongsuo \
    && /usr/local/tongsuo/bin/openssl version \
    && ln -sf /usr/local/tongsuo/bin/openssl /usr/local/bin/openssl-gm \
    && ln -sf /usr/local/tongsuo/bin/openssl /usr/local/bin/gmssl

# 目录与用户
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
        /opt/tsa-demo/config \
    && useradd -r -s /sbin/nologin fcgiwrap 2>/dev/null || true \
    && useradd -r -u 10001 -s /usr/sbin/nologin tsademo 2>/dev/null || true \
    && chown -R fcgiwrap:fcgiwrap /var/www/tsa /var/lib/tsa /run/fcgiwrap \
    && chmod -R 755 /etc/tsa

# TSA 服务配置与脚本
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

# ★ 从阶段 1 拷贝原生二进制 (无 JVM)
COPY --from=native-demo /src/sdk-demo/target/tsa-demo /usr/local/bin/tsa-demo
COPY sdk-demo/src/main/resources/application.yml /opt/tsa-demo/config/application.yml

RUN chmod +x \
        /scripts/generate_certs.sh \
        /scripts/generate_tls_certs.sh \
        /scripts/entrypoint.sh \
        /scripts/healthcheck.sh \
        /var/www/tsa/tsa_cgi.sh \
        /usr/local/bin/tsa-demo \
    && chown -R tsademo:tsademo /opt/tsa-demo

# 80/443: nginx (TSA + /api 反代)  9090: Demo 直连
EXPOSE 80 443 9090

HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=5 \
    CMD /scripts/healthcheck.sh

ENTRYPOINT ["/scripts/entrypoint.sh"]
