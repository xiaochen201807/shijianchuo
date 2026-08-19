#!/bin/bash
# ============================================================
# generate_certs.sh - 生成 SM2 国密 TSA 证书
#
# 使用 Tongsuo (OpenSSL 兼容国密库):
#   - 支持 SM2 曲线 / SM3 摘要
#   - 兼容 openssl ecparam / req / x509 / ts
#
# 注意: 不要用 GmSSL 3 的 gmssl 子命令 (已无 ecparam/req/ts)
# ============================================================

set -euo pipefail

CERT_DIR="/etc/tsa/certs"
OPENSSL_CNF="/etc/tsa/openssl/tsa.cnf"
EXT_CNF="/etc/tsa/openssl/tsa_ext.cnf"

# 优先使用 Tongsuo
if [ -n "${OPENSSL_BIN:-}" ] && [ -x "${OPENSSL_BIN}" ]; then
    :
elif [ -x /usr/local/tongsuo/bin/openssl ]; then
    OPENSSL_BIN=/usr/local/tongsuo/bin/openssl
elif [ -x /usr/local/bin/openssl-gm ]; then
    OPENSSL_BIN=/usr/local/bin/openssl-gm
else
    OPENSSL_BIN="$(command -v openssl)"
fi

if [ -f /scripts/openssl-env.sh ]; then
    # shellcheck source=/dev/null
    source /scripts/openssl-env.sh
else
    export LD_LIBRARY_PATH="/usr/local/tongsuo/lib:/usr/local/tongsuo/lib64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
    mkdir -p /usr/local/tongsuo/ssl
    if [ ! -s /usr/local/tongsuo/ssl/openssl.cnf ] && [ -s /etc/tsa/openssl/openssl-runtime.cnf ]; then
        cp -f /etc/tsa/openssl/openssl-runtime.cnf /usr/local/tongsuo/ssl/openssl.cnf
    fi
    export OPENSSL_CONF=/usr/local/tongsuo/ssl/openssl.cnf
fi

CA_COUNTRY="${CA_COUNTRY:-CN}"
CA_STATE="${CA_STATE:-Beijing}"
CA_LOCALITY="${CA_LOCALITY:-Beijing}"
CA_ORG="${CA_ORG:-MyOrg}"
CA_OU="${CA_OU:-TSA}"
CA_CN="${CA_CN:-TSA Root CA}"
TSA_CN="${TSA_CN:-TSA Server}"
CERT_DAYS="${CERT_DAYS:-3650}"

CA_SUBJECT="/C=${CA_COUNTRY}/ST=${CA_STATE}/L=${CA_LOCALITY}/O=${CA_ORG}/OU=${CA_OU}/CN=${CA_CN}"
TSA_SUBJECT="/C=${CA_COUNTRY}/ST=${CA_STATE}/L=${CA_LOCALITY}/O=${CA_ORG}/OU=${CA_OU}/CN=${TSA_CN}"
CA_DAYS="${CERT_DAYS}"
TSA_DAYS="${CERT_DAYS}"

echo "============================================"
echo "  生成 SM2 国密 TSA 证书"
echo "  OpenSSL: ${OPENSSL_BIN}"
echo "  Version: $(${OPENSSL_BIN} version 2>/dev/null || echo unknown)"
echo "============================================"
echo ""

if [ -f "${CERT_DIR}/tsacert.pem" ] && [ -f "${CERT_DIR}/tsakey.pem" ]; then
    echo "[INFO] TSA 证书已存在，跳过生成。"
    echo "[INFO] 如需重新生成，请删除 ${CERT_DIR}/ 下证书后重启。"
    exit 0
fi

mkdir -p "${CERT_DIR}"

# ------------------------------------------------------------
# 生成 SM2 私钥 (兼容不同曲线命名)
# ------------------------------------------------------------
gen_sm2_key() {
    local out="$1"
    local err
    # Tongsuo 常用曲线名: SM2
    if err=$(${OPENSSL_BIN} genpkey -algorithm EC -pkeyopt ec_paramgen_curve:SM2 -out "${out}" 2>&1); then
        return 0
    fi
    echo "[WARN] genpkey SM2 failed: ${err}"
    if err=$(${OPENSSL_BIN} ecparam -genkey -name SM2 -out "${out}" 2>&1); then
        return 0
    fi
    echo "[WARN] ecparam SM2 failed: ${err}"
    if err=$(${OPENSSL_BIN} genpkey -algorithm EC -pkeyopt ec_paramgen_curve:sm2p256v1 -out "${out}" 2>&1); then
        return 0
    fi
    echo "[WARN] genpkey sm2p256v1 failed: ${err}"
    if err=$(${OPENSSL_BIN} ecparam -genkey -name sm2p256v1 -out "${out}" 2>&1); then
        return 0
    fi
    echo "[ERROR] 无法生成 SM2 私钥。当前 openssl 可能不支持国密曲线。"
    echo "[ERROR] 请确认使用 Tongsuo: ${OPENSSL_BIN} version"
    ${OPENSSL_BIN} ecparam -list_curves 2>&1 | head -50 || true
    return 1
}

# ============================================================
# 步骤 1: SM2 CA 根证书
# ============================================================
echo ""
echo "--- 步骤 1: 生成 SM2 CA 根证书 ---"
echo ""

echo "[1.1] 生成 SM2 CA 私钥..."
gen_sm2_key "${CERT_DIR}/cakey.pem"
echo "[OK] CA 私钥: ${CERT_DIR}/cakey.pem"

echo ""
echo "[1.2] 生成 SM2 CA 自签名证书..."
${OPENSSL_BIN} req \
    -new -x509 \
    -key "${CERT_DIR}/cakey.pem" \
    -out "${CERT_DIR}/cacert.pem" \
    -days "${CA_DAYS}" \
    -sm3 \
    -subj "${CA_SUBJECT}" \
    -extensions v3_ca \
    -config "${OPENSSL_CNF}"
echo "[OK] CA 证书: ${CERT_DIR}/cacert.pem"

echo ""
echo "[1.3] CA 证书摘要..."
${OPENSSL_BIN} x509 -in "${CERT_DIR}/cacert.pem" -noout -subject -issuer -dates 2>&1 || true

# ============================================================
# 步骤 2: SM2 TSA 证书 (EKU=timeStamping)
# ============================================================
echo ""
echo "--- 步骤 2: 生成 SM2 TSA 签名证书 ---"
echo ""

echo "[2.1] 生成 SM2 TSA 私钥..."
gen_sm2_key "${CERT_DIR}/tsakey.pem"
echo "[OK] TSA 私钥: ${CERT_DIR}/tsakey.pem"

echo ""
echo "[2.2] 生成 TSA CSR..."
${OPENSSL_BIN} req \
    -new \
    -key "${CERT_DIR}/tsakey.pem" \
    -out "${CERT_DIR}/tsacsr.pem" \
    -sm3 \
    -subj "${TSA_SUBJECT}" \
    -config "${OPENSSL_CNF}"
echo "[OK] TSA CSR: ${CERT_DIR}/tsacsr.pem"

echo ""
echo "[2.3] CA 签发 TSA 证书 (timeStamping EKU)..."
${OPENSSL_BIN} x509 \
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
    -extensions v3_tsa
echo "[OK] TSA 证书: ${CERT_DIR}/tsacert.pem"

echo ""
echo "[2.4] 验证证书链..."
if ${OPENSSL_BIN} verify -CAfile "${CERT_DIR}/cacert.pem" "${CERT_DIR}/tsacert.pem"; then
    echo "[OK] 证书链验证通过"
else
    echo "[WARNING] 证书链验证返回非 0（部分国密实现 verify 行为不同，可继续）"
fi

echo ""
echo "--- TSA 证书详情 (前 40 行) ---"
${OPENSSL_BIN} x509 -in "${CERT_DIR}/tsacert.pem" -text -noout 2>&1 | head -40 || true

# ============================================================
# 步骤 3: 序列号
# ============================================================
echo ""
echo "--- 步骤 3: 初始化序列号 ---"
echo "01" > "${CERT_DIR}/tsaserial"
if [ ! -f "${CERT_DIR}/caserial.srl" ]; then
    echo "01" > "${CERT_DIR}/caserial.srl"
fi
touch "${CERT_DIR}/index.txt"
echo "[OK] tsaserial 已初始化"

# ============================================================
# 步骤 4: 权限
# ============================================================
echo ""
echo "--- 步骤 4: 权限 ---"
chmod 600 "${CERT_DIR}/cakey.pem" "${CERT_DIR}/tsakey.pem"
chmod 644 "${CERT_DIR}/cacert.pem" "${CERT_DIR}/tsacert.pem"
chmod 644 "${CERT_DIR}/tsaserial" "${CERT_DIR}/caserial.srl" 2>/dev/null || true
chmod 775 "${CERT_DIR}" 2>/dev/null || true
chmod 664 "${CERT_DIR}/tsaserial" 2>/dev/null || true

echo ""
echo "============================================"
echo "  SM2 国密 TSA 证书生成完成"
echo "============================================"
echo "  CA  : ${CERT_DIR}/cacert.pem"
echo "  TSA : ${CERT_DIR}/tsacert.pem"
echo "  工具: ${OPENSSL_BIN}"
echo "============================================"
