#!/bin/bash
# ============================================================
# 生成 Nginx HTTPS 自签名证书（传输层）
# ============================================================

set -euo pipefail

TLS_CERT_DIR="/etc/nginx/tls"

# --- 定位 Tongsuo openssl ---
if [ -n "${OPENSSL_BIN:-}" ] && [ -x "${OPENSSL_BIN}" ]; then
    :
elif [ -x /usr/local/tongsuo/bin/openssl ]; then
    OPENSSL_BIN=/usr/local/tongsuo/bin/openssl
elif command -v openssl >/dev/null 2>&1; then
    OPENSSL_BIN="$(command -v openssl)"
else
    echo "[ERROR] 未找到 openssl"
    echo "[ERROR] PATH=${PATH}"
    ls -la /usr/local/tongsuo/bin/ 2>/dev/null || true
    exit 1
fi

export PATH="$(dirname "${OPENSSL_BIN}"):${PATH}"
export LD_LIBRARY_PATH="/usr/local/tongsuo/lib:/usr/local/tongsuo/lib64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

# 关键: 镜像未装系统 openssl 时，默认 OPENSSL_CONF 可能指向不存在的
# /etc/ssl/openssl.cnf，导致 genrsa/req 直接失败（旧脚本 2>/dev/null 会静默 exit 1）
if [ -f /usr/local/tongsuo/ssl/openssl.cnf ]; then
    export OPENSSL_CONF=/usr/local/tongsuo/ssl/openssl.cnf
elif [ -f /usr/local/tongsuo/ssl/openssl.cnf.dist ]; then
    export OPENSSL_CONF=/usr/local/tongsuo/ssl/openssl.cnf.dist
else
    unset OPENSSL_CONF || true
fi

echo "[INFO] 生成 Nginx TLS 自签名证书..."
echo "[INFO] OPENSSL_BIN=${OPENSSL_BIN}"
echo "[INFO] OPENSSL_CONF=${OPENSSL_CONF:-<unset>}"
echo "[INFO] LD_LIBRARY_PATH=${LD_LIBRARY_PATH}"
"${OPENSSL_BIN}" version

mkdir -p "${TLS_CERT_DIR}"
if [ ! -w "${TLS_CERT_DIR}" ]; then
    echo "[ERROR] 目录不可写: ${TLS_CERT_DIR}"
    ls -la /etc/nginx/ || true
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
        echo "----- openssl 错误输出 -----"
        cat "${ERR}" 2>/dev/null || true
        echo "----- 诊断 -----"
        ldd "${OPENSSL_BIN}" 2>/dev/null | head -20 || true
        ls -la "${TLS_CERT_DIR}" || true
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
    echo "----- openssl 错误输出 -----"
    cat "${ERR}" 2>/dev/null || true
    exit 1
fi

chmod 600 "${TLS_CERT_DIR}/tls_key.pem"
chmod 644 "${TLS_CERT_DIR}/tls_cert.pem"
rm -f "${ERR}"

echo "[OK] TLS 证书: ${TLS_CERT_DIR}/tls_cert.pem"
ls -la "${TLS_CERT_DIR}/tls_key.pem" "${TLS_CERT_DIR}/tls_cert.pem"
