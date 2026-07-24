#!/usr/bin/env bash
# ============================================================
# 本地构建 tsa-demo 原生二进制 (Linux/macOS)
# 需要: GraalVM 21 + native-image
#
#   bash ./scripts/build-native.sh
# 产物: sdk-demo/target/tsa-demo
# ============================================================
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if ! command -v native-image >/dev/null 2>&1; then
  echo "ERROR: native-image not found. Install GraalVM 21 + native-image."
  echo "Or: docker compose -f docker-compose.demo.yml up --build"
  exit 1
fi

native-image --version
java -version

echo "==> Install SDK jar..."
mvn -B -f pom.xml -pl sdk -am clean install -DskipTests

echo "==> Native compile demo..."
mvn -B -f pom.xml -pl sdk-demo -am -Pnative -DskipTests package

BIN="$ROOT/sdk-demo/target/tsa-demo"
test -x "$BIN"
ls -lh "$BIN"
file "$BIN" || true

echo ""
echo "OK: $BIN"
echo "Run: $BIN"
echo "Test: curl 'http://localhost:9090/api/sm3/hash?text=Hello'"
