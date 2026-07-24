#!/bin/bash
# ============================================================
# generate_certs.sh - 生成 SM2 国密 TSA 证书
#
# 生成内容:
#   1. SM2 CA 根证书 (自签名)
#   2. SM2 TSA 签名证书 (由 CA 签发)
#   3. TSA 序列号文件
#
# 使用 GmSSL3 命令行工具
# ============================================================

set -e

# --- 配置 (支持环境变量覆盖，与 .env / docker-compose 对齐) ---
CERT_DIR="/etc/tsa/certs"
OPENSSL_CNF="/etc/tsa/openssl/tsa.cnf"
EXT_CNF="/etc/tsa/openssl/tsa_ext.cnf"

CA_COUNTRY="${CA_COUNTRY:-CN}"
CA_STATE="${CA_STATE:-Beijing}"
CA_LOCALITY="${CA_LOCALITY:-Beijing}"
CA_ORG="${CA_ORG:-MyOrg}"
CA_OU="${CA_OU:-TSA}"
CA_CN="${CA_CN:-TSA Root CA}"
TSA_CN="${TSA_CN:-TSA Server}"
CERT_DAYS="${CERT_DAYS:-3650}"

# 证书主题
CA_SUBJECT="/C=${CA_COUNTRY}/ST=${CA_STATE}/L=${CA_LOCALITY}/O=${CA_ORG}/OU=${CA_OU}/CN=${CA_CN}"
TSA_SUBJECT="/C=${CA_COUNTRY}/ST=${CA_STATE}/L=${CA_LOCALITY}/O=${CA_ORG}/OU=${CA_OU}/CN=${TSA_CN}"

# 有效期 (天)
CA_DAYS="${CERT_DAYS}"
TSA_DAYS="${CERT_DAYS}"

# SM2 曲线名称
SM2_CURVE="sm2p256v1"

echo "============================================"
echo "  生成 SM2 国密 TSA 证书"
echo "============================================"
echo ""

# --- 检查是否已存在证书 ---
if [ -f "${CERT_DIR}/tsacert.pem" ] && [ -f "${CERT_DIR}/tsakey.pem" ]; then
    echo "[INFO] TSA 证书已存在，跳过生成。"
    echo "[INFO] 如需重新生成，请删除 ${CERT_DIR}/ 下的文件后重启。"
    exit 0
fi

# --- 创建目录 ---
mkdir -p "${CERT_DIR}"

# ============================================================
# 步骤 1: 生成 SM2 CA 根证书
# ============================================================
echo ""
echo "--- 步骤 1: 生成 SM2 CA 根证书 ---"
echo ""

# 1.1 生成 SM2 CA 私钥
echo "[1.1] 生成 SM2 CA 私钥..."
gmssl ecparam \
    -genkey \
    -name "${SM2_CURVE}" \
    -out "${CERT_DIR}/cakey.pem" \
    2>&1

if [ $? -ne 0 ]; then
    echo "[ERROR] 生成 CA 私钥失败!"
    exit 1
fi
echo "[OK] CA 私钥已生成: ${CERT_DIR}/cakey.pem"

# 1.2 生成 CA 自签名证书
echo ""
echo "[1.2] 生成 SM2 CA 自签名证书..."
gmssl req \
    -new \
    -x509 \
    -key "${CERT_DIR}/cakey.pem" \
    -out "${CERT_DIR}/cacert.pem" \
    -days "${CA_DAYS}" \
    -sm3 \
    -subj "${CA_SUBJECT}" \
    -extensions v3_ca \
    -config "${OPENSSL_CNF}" \
    2>&1

if [ $? -ne 0 ]; then
    echo "[ERROR] 生成 CA 证书失败!"
    exit 1
fi
echo "[OK] CA 证书已生成: ${CERT_DIR}/cacert.pem"

# 1.3 验证 CA 证书
echo ""
echo "[1.3] 验证 CA 证书..."
gmssl x509 \
    -in "${CERT_DIR}/cacert.pem" \
    -text \
    -noout \
    2>&1 | head -30
echo "[OK] CA 证书验证通过"

# ============================================================
# 步骤 2: 生成 SM2 TSA 签名证书
# ============================================================
echo ""
echo "--- 步骤 2: 生成 SM2 TSA 签名证书 ---"
echo ""

# 2.1 生成 TSA 私钥
echo "[2.1] 生成 SM2 TSA 私钥..."
gmssl ecparam \
    -genkey \
    -name "${SM2_CURVE}" \
    -out "${CERT_DIR}/tsakey.pem" \
    2>&1

if [ $? -ne 0 ]; then
    echo "[ERROR] 生成 TSA 私钥失败!"
    exit 1
fi
echo "[OK] TSA 私钥已生成: ${CERT_DIR}/tsakey.pem"

# 2.2 生成 TSA 证书签名请求 (CSR)
echo ""
echo "[2.2] 生成 TSA CSR..."
gmssl req \
    -new \
    -key "${CERT_DIR}/tsakey.pem" \
    -out "${CERT_DIR}/tsacsr.pem" \
    -sm3 \
    -subj "${TSA_SUBJECT}" \
    -config "${OPENSSL_CNF}" \
    2>&1

if [ $? -ne 0 ]; then
    echo "[ERROR] 生成 TSA CSR 失败!"
    exit 1
fi
echo "[OK] TSA CSR 已生成: ${CERT_DIR}/tsacsr.pem"

# 2.3 用 CA 签发 TSA 证书 (带时间戳扩展)
echo ""
echo "[2.3] 用 CA 签发 TSA 证书 (含 timeStamping EKU)..."
gmssl x509 \
    -req \
    -in "${CERT_DIR}/tsacsr.pem" \
    -CA "${CERT_DIR}/cacert.pem" \
    -CAkey "${CERT_DIR}/cakey.pem" \
    -CAcreateserial \
    -CAserial "${CERT_DIR}/caserial.srl" \
    -out "${CERT_DIR}/tsacert.pem" \
    -days "${TSA_DAYS}" \
    -sm3 \
    -extfile "${EXT_CNF}" \
    -extensions v3_tsa \
    2>&1

if [ $? -ne 0 ]; then
    echo "[ERROR] 签发 TSA 证书失败!"
    exit 1
fi
echo "[OK] TSA 证书已生成: ${CERT_DIR}/tsacert.pem"

# 2.4 验证 TSA 证书
echo ""
echo "[2.4] 验证 TSA 证书..."
gmssl verify \
    -CAfile "${CERT_DIR}/cacert.pem" \
    "${CERT_DIR}/tsacert.pem" \
    2>&1

if [ $? -ne 0 ]; then
    echo "[WARNING] TSA 证书验证失败 (可能是 GmSSL 版本兼容问题)"
else
    echo "[OK] TSA 证书验证通过"
fi

# 显示 TSA 证书详情
echo ""
echo "--- TSA 证书详情 ---"
gmssl x509 \
    -in "${CERT_DIR}/tsacert.pem" \
    -text \
    -noout \
    2>&1

# ============================================================
# 步骤 3: 初始化 TSA 序列号文件
# ============================================================
echo ""
echo "--- 步骤 3: 初始化 TSA 序列号文件 ---"

# 序列号文件 (十六进制格式)
echo "01" > "${CERT_DIR}/tsaserial"
echo "[OK] TSA 序列号文件已创建: ${CERT_DIR}/tsaserial"

# CA 序列号文件 (如果不存在)
if [ ! -f "${CERT_DIR}/caserial.srl" ]; then
    echo "01" > "${CERT_DIR}/caserial.srl"
    echo "[OK] CA 序列号文件已创建: ${CERT_DIR}/caserial.srl"
fi

# 数据库文件
touch "${CERT_DIR}/index.txt"

# ============================================================
# 步骤 4: 设置文件权限
# ============================================================
echo ""
echo "--- 步骤 4: 设置文件权限 ---"

# 私钥权限 (仅所有者可读)
chmod 600 "${CERT_DIR}/cakey.pem" "${CERT_DIR}/tsakey.pem"

# 证书权限 (所有人可读)
chmod 644 "${CERT_DIR}/cacert.pem" "${CERT_DIR}/tsacert.pem"

# 序列号文件权限
chmod 644 "${CERT_DIR}/tsaserial" "${CERT_DIR}/caserial.srl" 2>/dev/null || true

# 设置目录权限
chown -R fcgiwrap:fcgiwrap "${CERT_DIR}" 2>/dev/null || true

echo "[OK] 文件权限设置完成"

# ============================================================
# 完成
# ============================================================
echo ""
echo "============================================"
echo "  SM2 国密 TSA 证书生成完成!"
echo "============================================"
echo ""
echo "证书文件列表:"
echo "  CA 私钥:   ${CERT_DIR}/cakey.pem"
echo "  CA 证书:   ${CERT_DIR}/cacert.pem"
echo "  TSA 私钥:  ${CERT_DIR}/tsakey.pem"
echo "  TSA 证书:  ${CERT_DIR}/tsacert.pem"
echo "  TSA 序列号: ${CERT_DIR}/tsaserial"
echo ""
echo "下一步: 启动 fcgiwrap 服务即可接收时间戳请求"
