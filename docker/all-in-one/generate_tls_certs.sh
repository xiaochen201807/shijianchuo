#!/bin/bash
# 生成 Nginx HTTPS 自签名证书（传输层，与 SM2 签名证书分离）

set -e

TLS_CERT_DIR="/etc/nginx/tls"
mkdir -p "${TLS_CERT_DIR}"

echo "[INFO] 生成 Nginx TLS 自签名证书..."

openssl genrsa -out "${TLS_CERT_DIR}/tls_key.pem" 2048 2>/dev/null

openssl req \
    -new -x509 \
    -key "${TLS_CERT_DIR}/tls_key.pem" \
    -out "${TLS_CERT_DIR}/tls_cert.pem" \
    -days 3650 \
    -subj "/C=CN/ST=Beijing/L=Beijing/O=MyOrg/OU=TSA/CN=tsa-all-in-one" \
    2>/dev/null

chmod 600 "${TLS_CERT_DIR}/tls_key.pem"
chmod 644 "${TLS_CERT_DIR}/tls_cert.pem"

echo "[OK] TLS 证书: ${TLS_CERT_DIR}/tls_cert.pem"
