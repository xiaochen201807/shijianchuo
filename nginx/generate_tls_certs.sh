#!/bin/bash
# ============================================================
# generate_tls_certs.sh - 生成 Nginx HTTPS 用的 TLS 自签名证书
#
# 注意: 这只是用于 HTTPS 传输层加密
#       与 TSA 时间戳签名证书 (SM2) 是分开的
# ============================================================

set -e

# TLS 证书独立目录，避免写入只读的 tsa-certs 卷
TLS_CERT_DIR="/etc/nginx/tls"

echo "[INFO] 生成 Nginx TLS 自签名证书..."

mkdir -p "${TLS_CERT_DIR}"

# 生成 RSA 2048 私钥
openssl genrsa \
    -out "${TLS_CERT_DIR}/tls_key.pem" \
    2048 \
    2>&1

echo "[OK] TLS 私钥已生成"

# 生成自签名证书
openssl req \
    -new \
    -x509 \
    -key "${TLS_CERT_DIR}/tls_key.pem" \
    -out "${TLS_CERT_DIR}/tls_cert.pem" \
    -days 3650 \
    -subj "/C=CN/ST=Beijing/L=Beijing/O=MyOrg/OU=TSA/CN=tsa-nginx" \
    2>&1

echo "[OK] TLS 证书已生成"

# 设置权限
chmod 600 "${TLS_CERT_DIR}/tls_key.pem"
chmod 644 "${TLS_CERT_DIR}/tls_cert.pem"

echo "[INFO] TLS 证书生成完成"
