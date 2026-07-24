#!/bin/bash
# ============================================================
# tsa_cgi.sh - RFC 3161 时间戳 CGI 脚本
#
# 功能:
#   1. 接收 HTTP POST 请求 (DER 编码的 TimeStampReq)
#   2. 使用 GmSSL3 生成 SM2/SM3 时间戳响应
#   3. 返回 DER 编码的 TimeStampResp
#
# 运行环境: fcgiwrap (FastCGI)
# 依赖: GmSSL3 (gmssl 命令)
# ============================================================

# --- 加载日志函数 ---
log_info() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] [INFO] $*" >> /var/log/tsa/tsa_cgi.log
}

log_error() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] [ERROR] $*" >> /var/log/tsa/tsa_error.log
}

# --- 检查请求方法 ---
if [ "${REQUEST_METHOD}" != "POST" ]; then
    log_error "Invalid method: ${REQUEST_METHOD}"
    printf "Status: 405 Method Not Allowed\r\n"
    printf "Content-Type: text/plain\r\n\r\n"
    printf "405 Method Not Allowed. Use POST with Content-Type: application/timestamp-query.\n"
    exit 0
fi

# --- 检查 Content-Type (可选，RFC 3161 不强制) ---
log_info "Incoming request: method=${REQUEST_METHOD}, content_length=${CONTENT_LENGTH}, content_type=${CONTENT_TYPE}"

# --- 检查 Content-Length ---
if [ -z "${CONTENT_LENGTH}" ] || [ "${CONTENT_LENGTH}" -le 0 ]; then
    log_error "Empty or missing Content-Length"
    printf "Status: 400 Bad Request\r\n"
    printf "Content-Type: text/plain\r\n\r\n"
    printf "400 Bad Request. Missing or empty Content-Length.\n"
    exit 0
fi

# --- 限制请求大小 (最大 10MB) ---
MAX_SIZE=$((10 * 1024 * 1024))
if [ "${CONTENT_LENGTH}" -gt "${MAX_SIZE}" ]; then
    log_error "Request too large: ${CONTENT_LENGTH} bytes"
    printf "Status: 413 Request Entity Too Large\r\n"
    printf "Content-Type: text/plain\r\n\r\n"
    printf "413 Request Entity Too Large. Maximum size is 10MB.\n"
    exit 0
fi

# --- 创建临时目录 ---
TMPDIR=$(mktemp -d /tmp/tsa_XXXXXX)
if [ $? -ne 0 ]; then
    log_error "Failed to create temp directory"
    printf "Status: 500 Internal Server Error\r\n"
    printf "Content-Type: text/plain\r\n\r\n"
    printf "500 Internal Server Error.\n"
    exit 0
fi

QUERY_FILE="${TMPDIR}/query.tsq"
RESP_FILE="${TMPDIR}/response.tsr"
ERR_FILE="${TMPDIR}/error.log"

# --- 读取请求体 (二进制安全) ---
# 使用 dd 读取精确长度的数据
dd bs=1 count="${CONTENT_LENGTH}" of="${QUERY_FILE}" 2>/dev/null < /dev/stdin

if [ $? -ne 0 ]; then
    log_error "Failed to read request body"
    printf "Status: 500 Internal Server Error\r\n"
    printf "Content-Type: text/plain\r\n\r\n"
    printf "500 Internal Server Error. Failed to read request body.\n"
    rm -rf "${TMPDIR}"
    exit 0
fi

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

# --- 使用 GmSSL3 生成时间戳响应 ---
# 调用 gmssl ts -reply 命令
# -queryfile:   输入的 TimeStampReq 文件
# -signer:      TSA 签名证书 (SM2)
# -inkey:       TSA 签名私钥 (SM2)
# -md:          摘要算法 (SM3 国密)
# -chain:       证书链 (CA 证书)
# -config:      GmSSL/OpenSSL 配置文件
# -section:     TSA 配置节名
log_info "Generating timestamp response with gmssl ts -reply"

gmssl ts -reply \
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
    log_error "gmssl ts -reply failed with status ${REPLY_STATUS}"
    log_error "Error output: $(cat "${ERR_FILE}")"
    
    printf "Status: 500 Internal Server Error\r\n"
    printf "Content-Type: text/plain\r\n\r\n"
    printf "500 Internal Server Error. Timestamp generation failed.\n"
    printf "\nError details:\n"
    cat "${ERR_FILE}"
    
    rm -rf "${TMPDIR}"
    exit 0
fi

# --- 读取响应文件大小 ---
RESP_SIZE=$(stat -c%s "${RESP_FILE}")
log_info "Response generated: ${RESP_SIZE} bytes"

# --- 返回时间戳响应 ---
# RFC 3161: Content-Type: application/timestamp-reply
printf "Status: 200 OK\r\n"
printf "Content-Type: application/timestamp-reply\r\n"
printf "Content-Length: %d\r\n" "${RESP_SIZE}"
printf "Cache-Control: no-store, no-cache, must-revalidate\r\n"
printf "Pragma: no-cache\r\n"
printf "\r\n"

# 输出二进制响应体
cat "${RESP_FILE}"

# --- 清理临时文件 ---
rm -rf "${TMPDIR}"
log_info "Request completed successfully"

exit 0
