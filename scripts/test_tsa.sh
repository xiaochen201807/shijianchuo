#!/usr/bin/env bash
# ============================================================
# test_tsa.sh - RFC 3161 国密 TSA 完整自测脚本 (bash)
# 用法: bash ./scripts/test_tsa.sh
# ============================================================

set -u

TSA_BASE="${TSA_BASE:-http://localhost:8080}"
DEMO_BASE="${DEMO_BASE:-http://localhost:9090}"

echo "RFC 3161 国密 TSA 自测"
echo "TSA Base : ${TSA_BASE}"
echo "Demo Base: ${DEMO_BASE}"
echo ""

echo "=== 1. 健康检查 ==="
curl -sS "${TSA_BASE}/health" || echo "FAIL"
echo ""

echo "=== 2. 服务信息 ==="
curl -sS "${TSA_BASE}/info" || echo "FAIL"
echo ""
echo ""

echo "=== 3. 下载证书 ==="
curl -sS "${TSA_BASE}/tsa/cert" -o tsacert.pem && echo "tsacert.pem OK" || echo "tsacert FAIL"
curl -sS "${TSA_BASE}/tsa/cacert" -o cacert.pem && echo "cacert.pem OK" || echo "cacert FAIL"
echo ""

echo "=== 4. SM3 摘要 (Demo) ==="
curl -sS "${DEMO_BASE}/api/sm3/hash?text=Hello%20TSA" || echo "FAIL (Demo 未启动?)"
echo ""
echo ""

echo "=== 5. SM2 密钥对 (Demo) ==="
curl -sS "${DEMO_BASE}/api/sm2/keypair" | head -c 400 || true
echo ""
echo ""

echo "=== 6. SM2 签名 (Demo) ==="
curl -sS -X POST "${DEMO_BASE}/api/sm2/sign" \
  -H "Content-Type: application/json" \
  -d '{"text":"Hello, TSA!"}' | head -c 500 || true
echo ""
echo ""

echo "=== 7. TSA 时间戳 (Demo -> TSA Server) ==="
curl -sS -X POST "${DEMO_BASE}/api/tsa/timestamp/text" \
  -H "Content-Type: application/json" \
  -d '{"text":"Hello, TSA!"}' || echo "FAIL"
echo ""
echo ""

echo "=== 自测结束 ==="
