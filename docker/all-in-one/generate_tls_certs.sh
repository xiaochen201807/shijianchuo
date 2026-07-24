#!/bin/bash
# ============================================================
# 生成 Nginx HTTPS 自签名证书（传输层，与 SM2 签名证书分离）
# 使用 Tongsuo/OpenSSL，失败时必须打印错误、非 0 退出
# ============================================================

set -euo pipefail

TLS_CERT_DIR="/etc/nginx/tls"

# 优先 Tongsuo（与 entrypoint 一致）
if [ -n "${OPENSSL_BIN:-}" ] && [ -x "${OPENSSL_BIN}" ]; then
    :
elif [ -x /usr/local/tongsuo/bin/openssl ]; then
    OPENSSL_BIN=/usr/local/tongsuo/bin/openssl
elif command -v openssl >/dev/null 2>&1; then
    OPENSSL_BIN="$(command -v openssl)"
else
    echo "[ERROR] 未找到 openssl（Tongsuo）。PATH=${PATH}"
    exit 1
fi

export LD_LIBRARY_PATH="/usr/local/tongsuo/lib:/usr/local/tongsuo/lib64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

echo "[INFO] 生成 Nginx TLS 自签名证书..."
echo "[INFO] OPENSSL_BIN=${OPENSSL_BIN}"
"${OPENSSL_BIN}" version || true

mkdir -p "${TLS_CERT_DIR}"
if [ ! -w "${TLS_CERT_DIR}" ]; then
    echo "[ERROR] 目录不可写: ${TLS_CERT_DIR}"
    ls -la "$(dirname "${TLS_CERT_DIR}")" || true
    exit 1
fi

# OpenSSL 3 / Tongsuo：优先 genpkey，失败再试 genrsa
echo "[INFO] 生成 RSA 私钥..."
if ! "${OPENSSL_BIN}" genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
        -out "${TLS_CERT_DIR}/tls_key.pem" 2>"${TLS_CERT_DIR}/tls_gen.err"; then
    echo "[WARN] genpkey 失败，尝试 genrsa..."
    cat "${TLS_CERT_DIR}/tls_gen.err" || true
    if ! "${OPENSSL_BIN}" genrsa -out "${TLS_CERT_DIR}/tls_key.pem" 2048 \
            2>"${TLS_CERT_DIR}/tls_gen.err"; then
        echo "[ERROR] 生成 TLS 私钥失败:"
        cat "${TLS_CERT_DIR}/tls_gen.err" || true
        exit 1
    fi
fi

if [ ! -s "${TLS_CERT_DIR}/tls_key.pem" ]; then
    echo "[ERROR] 私钥文件为空: ${TLS_CERT_DIR}/tls_key.pem"
    exit 1
fi
echo "[OK] TLS 私钥已生成"

echo "[INFO] 生成自签名证书..."
if ! "${OPENSSL_BIN}" req -new -x509 \
        -key "${TLS_CERT_DIR}/tls_key.pem" \
        -out "${TLS_CERT_DIR}/tls_cert.pem" \
        -days 3650 \
        -subj "/C=CN/ST=Beijing/L=Beijing/O=MyOrg/OU=TSA/CN=tsa-all-in-one" \
        2>"${TLS_CERT_DIR}/tls_gen.err"; then
    echo "[ERROR] 生成 TLS 证书失败:"
    cat "${TLS_CERT_DIR}/tls_gen.err" || true
    exit 1
fi

if [ ! -s "${TLS_CERT_DIR}/tls_cert.pem" ]; then
    echo "[ERROR] 证书文件为空: ${TLS_CERT_DIR}/tls_cert.pem"
    exit 1
fi

chmod 600 "${TLS_CERT_DIR}/tls_key.pem"
chmod 644 "${TLS_CERT_DIR}/tls_cert.pem"
rm -f "${TLS_CERT_DIR}/tls_gen.err"

echo "[OK] TLS 证书: ${TLS_CERT_DIR}/tls_cert.pem"
ls -la "${TLS_CERT_DIR}/tls_key.pem" "${TLS_CERT_DIR}/tls_cert.pem"
