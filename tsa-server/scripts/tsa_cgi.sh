#!/bin/bash
# ============================================================
# tsa_cgi.sh - RFC 3161 时间戳 CGI
#
# 使用 Tongsuo openssl ts -reply (SM2 证书 + SM3 摘要)
# ============================================================

log_info() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] [INFO] $*" >> /var/log/tsa/tsa_cgi.log
}

log_error() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] [ERROR] $*" >> /var/log/tsa/tsa_error.log
}

# 国密 OpenSSL (Tongsuo)
if [ -n "${OPENSSL_BIN:-}" ] && [ -x "${OPENSSL_BIN}" ]; then
    :
elif [ -x /usr/local/tongsuo/bin/openssl ]; then
    OPENSSL_BIN=/usr/local/tongsuo/bin/openssl
elif [ -x /usr/local/bin/openssl-gm ]; then
    OPENSSL_BIN=/usr/local/bin/openssl-gm
else
    OPENSSL_BIN="$(command -v openssl || true)"
fi
export LD_LIBRARY_PATH="/usr/local/tongsuo/lib:/usr/local/tongsuo/lib64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
if [ -f /usr/local/tongsuo/ssl/openssl.cnf ]; then
    export OPENSSL_CONF=/usr/local/tongsuo/ssl/openssl.cnf
else
    unset OPENSSL_CONF || true
fi

if [ "${REQUEST_METHOD}" != "POST" ]; then
    log_error "Invalid method: ${REQUEST_METHOD}"
    printf "Status: 405 Method Not Allowed\r\n"
    printf "Content-Type: text/plain\r\n\r\n"
    printf "405 Method Not Allowed. Use POST with Content-Type: application/timestamp-query.\n"
    exit 0
fi

log_info "Incoming request: method=${REQUEST_METHOD}, content_length=${CONTENT_LENGTH}, content_type=${CONTENT_TYPE}, openssl=${OPENSSL_BIN}"

if [ -z "${CONTENT_LENGTH}" ] || [ "${CONTENT_LENGTH}" -le 0 ]; then
    log_error "Empty or missing Content-Length"
    printf "Status: 400 Bad Request\r\n"
    printf "Content-Type: text/plain\r\n\r\n"
    printf "400 Bad Request. Missing or empty Content-Length.\n"
    exit 0
fi

MAX_SIZE=$((10 * 1024 * 1024))
if [ "${CONTENT_LENGTH}" -gt "${MAX_SIZE}" ]; then
    log_error "Request too large: ${CONTENT_LENGTH} bytes"
    printf "Status: 413 Request Entity Too Large\r\n"
    printf "Content-Type: text/plain\r\n\r\n"
    printf "413 Request Entity Too Large. Maximum size is 10MB.\n"
    exit 0
fi

TMPDIR=$(mktemp -d /tmp/tsa_XXXXXX) || {
    log_error "Failed to create temp directory"
    printf "Status: 500 Internal Server Error\r\n"
    printf "Content-Type: text/plain\r\n\r\n"
    printf "500 Internal Server Error.\n"
    exit 0
}

QUERY_FILE="${TMPDIR}/query.tsq"
RESP_FILE="${TMPDIR}/response.tsr"
ERR_FILE="${TMPDIR}/error.log"

dd bs=1 count="${CONTENT_LENGTH}" of="${QUERY_FILE}" 2>/dev/null < /dev/stdin || {
    log_error "Failed to read request body"
    printf "Status: 500 Internal Server Error\r\n"
    printf "Content-Type: text/plain\r\n\r\n"
    printf "500 Internal Server Error. Failed to read request body.\n"
    rm -rf "${TMPDIR}"
    exit 0
}

QUERY_SIZE=$(stat -c%s "${QUERY_FILE}" 2>/dev/null || echo 0)
log_info "Query file size: ${QUERY_SIZE} bytes"

if [ "${QUERY_SIZE}" -eq 0 ]; then
    log_error "Empty query file"
    printf "Status: 400 Bad Request\r\n"
    printf "Content-Type: text/plain\r\n\r\n"
    printf "400 Bad Request. Empty request body.\n"
    rm -rf "${TMPDIR}"
    exit 0
fi

if [ ! -x "${OPENSSL_BIN}" ]; then
    log_error "OPENSSL_BIN not found: ${OPENSSL_BIN}"
    printf "Status: 500 Internal Server Error\r\n"
    printf "Content-Type: text/plain\r\n\r\n"
    printf "500 Internal Server Error. OpenSSL (Tongsuo) not available.\n"
    rm -rf "${TMPDIR}"
    exit 0
fi

log_info "Generating timestamp with: ${OPENSSL_BIN} ts -reply -md sm3"

# RFC 3161 TimeStampResp
# -md sm3: 国密摘要；签名算法由 SM2 证书决定
"${OPENSSL_BIN}" ts -reply \
    -queryfile "${QUERY_FILE}" \
    -signer /etc/tsa/certs/tsacert.pem \
    -inkey /etc/tsa/certs/tsakey.pem \
    -md sm3 \
    -chain /etc/tsa/certs/cacert.pem \
    -config /etc/tsa/openssl/tsa.cnf \
    -section tsa \
    -out "${RESP_FILE}" \
    2> "${ERR_FILE}"

REPLY_STATUS=$?

if [ ${REPLY_STATUS} -ne 0 ] || [ ! -s "${RESP_FILE}" ]; then
    log_error "ts -reply failed status=${REPLY_STATUS}"
    log_error "Error: $(cat "${ERR_FILE}" 2>/dev/null)"

    printf "Status: 500 Internal Server Error\r\n"
    printf "Content-Type: text/plain\r\n\r\n"
    printf "500 Internal Server Error. Timestamp generation failed.\n"
    printf "\nError details:\n"
    cat "${ERR_FILE}"

    rm -rf "${TMPDIR}"
    exit 0
fi

RESP_SIZE=$(stat -c%s "${RESP_FILE}")
log_info "Response generated: ${RESP_SIZE} bytes"

printf "Status: 200 OK\r\n"
printf "Content-Type: application/timestamp-reply\r\n"
printf "Content-Length: %d\r\n" "${RESP_SIZE}"
printf "Cache-Control: no-store, no-cache, must-revalidate\r\n"
printf "Pragma: no-cache\r\n"
printf "\r\n"
cat "${RESP_FILE}"

rm -rf "${TMPDIR}"
log_info "Request completed successfully"
exit 0
