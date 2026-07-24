# ============================================================
# 本地构建 tsa-demo 原生二进制 (Windows)
# 需要: GraalVM 21 + native-image + Visual Studio Build Tools (MSVC)
#
#   pwsh ./scripts/build-native.ps1
# 产物: sdk-demo/target/tsa-demo.exe
# ============================================================

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Root

Write-Host "==> Checking native-image..." -ForegroundColor Cyan
$ni = Get-Command native-image -ErrorAction SilentlyContinue
if (-not $ni) {
    Write-Host "ERROR: native-image not found. Install GraalVM 21 and run:" -ForegroundColor Red
    Write-Host "  gu install native-image"
    Write-Host "Or use Docker: docker compose -f docker-compose.demo.yml up --build"
    exit 1
}
native-image --version
java -version

Write-Host "==> Install SDK jar..." -ForegroundColor Cyan
mvn -B -f pom.xml -pl sdk -am clean install -DskipTests

Write-Host "==> Native compile demo..." -ForegroundColor Cyan
mvn -B -f pom.xml -pl sdk-demo -am -Pnative -DskipTests package

$bin = Join-Path $Root "sdk-demo\target\tsa-demo.exe"
if (-not (Test-Path $bin)) {
    $bin = Join-Path $Root "sdk-demo\target\tsa-demo"
}
if (-not (Test-Path $bin)) {
    Write-Host "ERROR: binary not found under sdk-demo/target/" -ForegroundColor Red
    Get-ChildItem (Join-Path $Root "sdk-demo\target") | Format-Table Name, Length
    exit 1
}

Write-Host ""
Write-Host "OK: $bin" -ForegroundColor Green
Get-Item $bin | Format-List FullName, Length, LastWriteTime
Write-Host "Run:"
Write-Host "  $bin"
Write-Host "  curl http://localhost:9090/api/sm3/hash?text=Hello"
