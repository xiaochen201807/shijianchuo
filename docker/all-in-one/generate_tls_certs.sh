#!/bin/bash
# ============================================================
# 生成 Nginx HTTPS 自签名证书（传输层）
# ============================================================

set -euo pipefail

TLS_CERT_DIR="/etc/nginx/tls"

# 加载 Tongsuo 环境 + 确保 openssl.cnf 存在
if [ -f /scripts/openssl-env.sh ]; then
    # shellcheck source=/dev/null
    source /scripts/openssl-env.sh
else
    export PATH="/usr/local/tongsuo/bin:${PATH}"
    export LD_LIBRARY_PATH="/usr/local/tongsuo/lib:/usr/local/tongsuo/lib64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
    export OPENSSL_BIN="${OPENSSL_BIN:-/usr/local/tongsuo/bin/openssl}"
    mkdir -p /usr/local/tongsuo/ssl
    if [ ! -s /usr/local/tongsuo/ssl/openssl.cnf ] && [ -s /etc/tsa/openssl/openssl-runtime.cnf ]; then
        cp -f /etc/tsa/openssl/openssl-runtime.cnf /usr/local/tongsuo/ssl/openssl.cnf
    fi
    export OPENSSL_CONF=/usr/local/tongsuo/ssl/openssl.cnf
fi

if [ -z "${OPENSSL_BIN:-}" ] || [ ! -x "${OPENSSL_BIN}" ]; then
    echo "[ERROR] 未找到可用的 openssl: OPENSSL_BIN=${OPENSSL_BIN:-}"
    exit 1
fi

echo "[INFO] 生成 Nginx TLS 自签名证书..."
echo "[INFO] OPENSSL_BIN=${OPENSSL_BIN}"
echo "[INFO] OPENSSL_CONF=${OPENSSL_CONF:-<unset>}"
ls -la "${OPENSSL_CONF}" || {
    echo "[ERROR] OPENSSL_CONF 文件不存在: ${OPENSSL_CONF}"
    exit 1
}
"${OPENSSL_BIN}" version

mkdir -p "${TLS_CERT_DIR}"
if [ ! -w "${TLS_CERT_DIR}" ]; then
    echo "[ERROR] 目录不可写: ${TLS_CERT_DIR}"
    exit 1
fi

ERR="${TLS_CERT_DIR}/.tls_gen.err"
rm -f "${ERR}" "${TLS_CERT_DIR}/tls_key.pem" "${TLS_CERT_DIR}/tls_cert.pem"

echo "[INFO] 生成 RSA 私钥 (genpkey)..."
set +e
"${OPENSSL_BIN}" genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
    -out "${TLS_CERT_DIR}/tls_key.pem" 2>"${ERR}"
RC=$?
set -e
if [ "${RC}" -ne 0 ] || [ ! -s "${TLS_CERT_DIR}/tls_key.pem" ]; then
    echo "[WARN] genpkey 失败 (rc=${RC})，尝试 genrsa..."
    cat "${ERR}" 2>/dev/null || true
    set +e
    "${OPENSSL_BIN}" genrsa -out "${TLS_CERT_DIR}/tls_key.pem" 2048 2>"${ERR}"
    RC=$?
    set -e
    if [ "${RC}" -ne 0 ] || [ ! -s "${TLS_CERT_DIR}/tls_key.pem" ]; then
        echo "[ERROR] 生成 TLS 私钥失败 (rc=${RC})"
        cat "${ERR}" 2>/dev/null || true
        exit 1
    fi
fi
echo "[OK] 私钥: ${TLS_CERT_DIR}/tls_key.pem ($(wc -c < "${TLS_CERT_DIR}/tls_key.pem") bytes)"

echo "[INFO] 生成自签名证书 (req -x509)..."
set +e
"${OPENSSL_BIN}" req -new -x509 -nodes \
    -key "${TLS_CERT_DIR}/tls_key.pem" \
    -out "${TLS_CERT_DIR}/tls_cert.pem" \
    -days 3650 \
    -subj "/C=CN/ST=Beijing/L=Beijing/O=MyOrg/OU=TSA/CN=tsa-all-in-one" \
    2>"${ERR}"
RC=$?
set -e
if [ "${RC}" -ne 0 ] || [ ! -s "${TLS_CERT_DIR}/tls_cert.pem" ]; then
    echo "[ERROR] 生成 TLS 证书失败 (rc=${RC})"
    cat "${ERR}" 2>/dev/null || true
    exit 1
fi

chmod 600 "${TLS_CERT_DIR}/tls_key.pem"
chmod 644 "${TLS_CERT_DIR}/tls_cert.pem"
rm -f "${ERR}"

echo "[OK] TLS 证书: ${TLS_CERT_DIR}/tls_cert.pem"
ls -la "${TLS_CERT_DIR}/tls_key.pem" "${TLS_CERT_DIR}/tls_cert.pem"
