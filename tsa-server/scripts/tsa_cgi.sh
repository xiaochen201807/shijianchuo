#!/bin/bash
# ============================================================
# tsa_cgi.sh - RFC 3161 时间戳 CGI (Tongsuo)
#
# 关键:
#   - openssl 可能把提示打到 stdout，必须全部重定向，否则 nginx 502
#   - 不可 set -e（避免中途退出导致“no response received”）
#   - 摘要算法以 TimeStampReq 为准，不要传 -md sm3
# ============================================================

# 禁止任何未捕获错误导致静默退出；全程自己返回 CGI 头
set +e

log_info() {
    mkdir -p /var/log/tsa 2>/dev/null
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] [INFO] $*" >> /var/log/tsa/tsa_cgi.log 2>/dev/null
}

log_error() {
    mkdir -p /var/log/tsa 2>/dev/null
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] [ERROR] $*" >> /var/log/tsa/tsa_error.log 2>/dev/null
}

cgi_text() {
    # $1=status line e.g. "500 Internal Server Error", rest=body
    local status="$1"
    shift
    local body="$*"
    printf "Status: %s\r\n" "${status}"
    printf "Content-Type: text/plain; charset=utf-8\r\n"
    printf "Content-Length: %s\r\n" "$(printf '%s' "${body}" | wc -c)"
    printf "\r\n"
    printf '%s' "${body}"
}

# --- 环境 (不要向 stdout 打日志) ---
export PATH="/usr/local/tongsuo/bin:/usr/local/bin:/usr/bin:/bin"
export LD_LIBRARY_PATH="/usr/local/tongsuo/lib:/usr/local/tongsuo/lib64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export HOME="${HOME:-/var/lib/tsa}"
export TMPDIR="${TMPDIR:-/tmp}"

if [ -x /usr/local/tongsuo/bin/openssl ]; then
    OPENSSL_BIN=/usr/local/tongsuo/bin/openssl
elif [ -x /usr/local/bin/openssl-gm ]; then
    OPENSSL_BIN=/usr/local/bin/openssl-gm
else
    OPENSSL_BIN="$(command -v openssl 2>/dev/null)"
fi

# 确保 cnf 存在（fcgiwrap 用户可能无写 /usr/local，优先用已有文件）
if [ -s /usr/local/tongsuo/ssl/openssl.cnf ]; then
    export OPENSSL_CONF=/usr/local/tongsuo/ssl/openssl.cnf
elif [ -s /etc/tsa/openssl/openssl-runtime.cnf ]; then
    export OPENSSL_CONF=/etc/tsa/openssl/openssl-runtime.cnf
else
    export OPENSSL_CONF=/etc/tsa/openssl/tsa.cnf
fi

TSA_CONF=/etc/tsa/openssl/tsa.cnf
SIGNER=/etc/tsa/certs/tsacert.pem
INKEY=/etc/tsa/certs/tsakey.pem
CHAIN=/etc/tsa/certs/cacert.pem

# --- 方法检查 ---
if [ "${REQUEST_METHOD}" != "POST" ]; then
    log_error "Invalid method: ${REQUEST_METHOD}"
    cgi_text "405 Method Not Allowed" "405 Method Not Allowed. Use POST."
    exit 0
fi

log_info "req method=${REQUEST_METHOD} len=${CONTENT_LENGTH} type=${CONTENT_TYPE} openssl=${OPENSSL_BIN} conf=${OPENSSL_CONF}"

if [ -z "${CONTENT_LENGTH}" ] || [ "${CONTENT_LENGTH}" -le 0 ] 2>/dev/null; then
    log_error "bad Content-Length: ${CONTENT_LENGTH}"
    cgi_text "400 Bad Request" "400 Bad Request. Missing or empty Content-Length."
    exit 0
fi

if [ "${CONTENT_LENGTH}" -gt $((10 * 1024 * 1024)) ] 2>/dev/null; then
    cgi_text "413 Request Entity Too Large" "413 Request too large."
    exit 0
fi

if [ ! -x "${OPENSSL_BIN}" ]; then
    log_error "openssl missing: ${OPENSSL_BIN}"
    cgi_text "500 Internal Server Error" "500 openssl not found: ${OPENSSL_BIN}"
    exit 0
fi

for f in "${SIGNER}" "${INKEY}" "${TSA_CONF}"; do
    if [ ! -r "${f}" ]; then
        log_error "not readable: ${f}"
        cgi_text "500 Internal Server Error" "500 missing or unreadable: ${f}"
        exit 0
    fi
done

TMPDIR=$(mktemp -d /tmp/tsa_XXXXXX 2>/dev/null)
if [ -z "${TMPDIR}" ] || [ ! -d "${TMPDIR}" ]; then
    log_error "mktemp failed"
    cgi_text "500 Internal Server Error" "500 cannot create temp dir"
    exit 0
fi

QUERY_FILE="${TMPDIR}/query.tsq"
RESP_FILE="${TMPDIR}/response.tsr"
ERR_FILE="${TMPDIR}/error.log"
OUT_FILE="${TMPDIR}/openssl.stdout"

# 读请求体
dd bs=1 count="${CONTENT_LENGTH}" of="${QUERY_FILE}" 2>/dev/null < /dev/stdin
QUERY_SIZE=$(wc -c < "${QUERY_FILE}" 2>/dev/null | tr -d ' ')
log_info "query size=${QUERY_SIZE}"

if [ -z "${QUERY_SIZE}" ] || [ "${QUERY_SIZE}" -eq 0 ]; then
    rm -rf "${TMPDIR}"
    cgi_text "400 Bad Request" "400 empty body"
    exit 0
fi

# --- 调用 ts -reply（stdout/stderr 全部离开 CGI 通道）---
# Tongsuo 不支持 -md sm3；摘要以请求为准
run_reply() {
    # "$@" extra args
    "${OPENSSL_BIN}" ts -reply \
        -queryfile "${QUERY_FILE}" \
        -signer "${SIGNER}" \
        -inkey "${INKEY}" \
        -config "${TSA_CONF}" \
        -section tsa \
        -out "${RESP_FILE}" \
        "$@" \
        >"${OUT_FILE}" 2>"${ERR_FILE}"
    return $?
}

rm -f "${RESP_FILE}"
run_reply -chain "${CHAIN}"
REPLY_STATUS=$?

if [ "${REPLY_STATUS}" -ne 0 ] || [ ! -s "${RESP_FILE}" ]; then
    log_info "retry without -chain; err=$(tr '\n' ' ' < "${ERR_FILE}" 2>/dev/null)"
    rm -f "${RESP_FILE}"
    run_reply
    REPLY_STATUS=$?
fi

if [ "${REPLY_STATUS}" -ne 0 ] || [ ! -s "${RESP_FILE}" ]; then
    ERR_MSG=$(cat "${ERR_FILE}" "${OUT_FILE}" 2>/dev/null)
    log_error "ts -reply failed rc=${REPLY_STATUS} err=${ERR_MSG}"
    BODY="500 Timestamp generation failed (rc=${REPLY_STATUS}).
openssl=${OPENSSL_BIN}
OPENSSL_CONF=${OPENSSL_CONF}

${ERR_MSG}
"
    rm -rf "${TMPDIR}"
    cgi_text "500 Internal Server Error" "${BODY}"
    exit 0
fi

RESP_SIZE=$(wc -c < "${RESP_FILE}" | tr -d ' ')
log_info "response size=${RESP_SIZE}"

# 成功：标准 CGI 头 + 二进制体
printf "Status: 200 OK\r\n"
printf "Content-Type: application/timestamp-reply\r\n"
printf "Content-Length: %s\r\n" "${RESP_SIZE}"
printf "Cache-Control: no-store, no-cache, must-revalidate\r\n"
printf "Pragma: no-cache\r\n"
printf "\r\n"
cat "${RESP_FILE}"

rm -rf "${TMPDIR}"
log_info "ok"
exit 0
