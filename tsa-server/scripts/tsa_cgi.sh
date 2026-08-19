#!/bin/bash
# ============================================================
# tsa_cgi.sh - RFC 3161 时间戳 CGI (Tongsuo) - 极致优化版
#
# 优化点:
#   - 移除 date 命令（避免 fork）
#   - 合并文件清理
#   - 用 shell 重定向替代 cat
#   - 简化 dd 计算
# ============================================================

set +e

# 固定路径
TMPDIR="/tmp/tsa_cgi"
LOGDIR="/var/log/tsa"

# 预创建目录
[ -d "$TMPDIR" ] || mkdir -p "$TMPDIR" 2>/dev/null
[ -d "$LOGDIR" ] || mkdir -p "$LOGDIR" 2>/dev/null

# 日志函数（无时间戳，避免 date fork）
log_info() {
    echo "[INFO] $*" >> "$LOGDIR/tsa_cgi.log" 2>/dev/null
}

# CGI 错误响应
cgi_text() {
    printf "Status: %s\r\nContent-Type: text/plain\r\nContent-Length: %s\r\n\r\n%s" "$1" "${#2}" "$2"
}

# --- 环境 ---
export PATH="/usr/local/tongsuo/bin:/usr/local/bin:/usr/bin:/bin"
export LD_LIBRARY_PATH="/usr/local/tongsuo/lib:/usr/local/tongsuo/lib64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

# 固定路径
OPENSSL_BIN="/usr/local/tongsuo/bin/openssl"
TSA_CONF="/etc/tsa/openssl/tsa.cnf"
SIGNER="/etc/tsa/certs/tsacert.pem"
INKEY="/etc/tsa/certs/tsakey.pem"
CHAIN="/etc/tsa/certs/cacert.pem"

# 临时文件（用 PID 区分）
PID=$$
QUERY_FILE="${TMPDIR}/q${PID}"
RESP_FILE="${TMPDIR}/r${PID}"
ERR_FILE="${TMPDIR}/e${PID}"

# --- 快速检查 ---
[ "${REQUEST_METHOD}" != "POST" ] && { cgi_text "405" "Method Not Allowed"; exit 0; }
[ -z "${CONTENT_LENGTH}" ] || [ "${CONTENT_LENGTH}" -le 0 ] 2>/dev/null && { cgi_text "400" "Bad Request"; exit 0; }

# 读请求体（精确读取，无需 truncate）
dd if=/dev/stdin of="${QUERY_FILE}" bs="${CONTENT_LENGTH}" count=1 2>/dev/null

# 检查文件
[ ! -s "${QUERY_FILE}" ] && { cgi_text "400" "Empty body"; exit 0; }

# --- 调用 openssl ---
"${OPENSSL_BIN}" ts -reply \
    -queryfile "${QUERY_FILE}" \
    -signer "${SIGNER}" \
    -inkey "${INKEY}" \
    -config "${TSA_CONF}" \
    -section tsa \
    -chain "${CHAIN}" \
    -out "${RESP_FILE}" \
    2>"${ERR_FILE}"

# 失败则重试（不带 -chain）
if [ $? -ne 0 ] || [ ! -s "${RESP_FILE}" ]; then
    rm -f "${RESP_FILE}"
    "${OPENSSL_BIN}" ts -reply \
        -queryfile "${QUERY_FILE}" \
        -signer "${SIGNER}" \
        -inkey "${INKEY}" \
        -config "${TSA_CONF}" \
        -section tsa \
        -out "${RESP_FILE}" \
        2>"${ERR_FILE}"
fi

# 最终检查
if [ $? -ne 0 ] || [ ! -s "${RESP_FILE}" ]; then
    # 读取错误（避免 cat）
    ERR_MSG=$(<"${ERR_FILE}")
    rm -f "${QUERY_FILE}" "${RESP_FILE}" "${ERR_FILE}"
    cgi_text "500" "Timestamp failed: ${ERR_MSG}"
    exit 0
fi

# 成功响应（用 shell 重定向，避免 cat）
RESP_SIZE=$(stat -c%s "${RESP_FILE}" 2>/dev/null)
printf "Status: 200 OK\r\nContent-Type: application/timestamp-reply\r\nContent-Length: %s\r\n\r\n" "${RESP_SIZE}"
<"${RESP_FILE}"

# 统一清理
rm -f "${QUERY_FILE}" "${RESP_FILE}" "${ERR_FILE}"
exit 0
