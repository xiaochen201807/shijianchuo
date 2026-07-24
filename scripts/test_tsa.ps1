# ============================================================
# test_tsa.ps1 - RFC 3161 国密 TSA 完整自测脚本 (PowerShell)
# 用法: pwsh ./scripts/test_tsa.ps1
# ============================================================

$ErrorActionPreference = "Continue"
$TsaBase = if ($env:TSA_BASE) { $env:TSA_BASE } else { "http://localhost:8080" }
$DemoBase = if ($env:DEMO_BASE) { $env:DEMO_BASE } else { "http://localhost:9090" }

function Write-Step($n, $title) {
    Write-Host ""
    Write-Host "=== $n. $title ===" -ForegroundColor Cyan
}

function Invoke-Safe($url, $method = "GET", $body = $null) {
    try {
        if ($body) {
            return Invoke-RestMethod -Uri $url -Method $method -ContentType "application/json" -Body $body
        }
        return Invoke-RestMethod -Uri $url -Method $method
    } catch {
        Write-Host "  [FAIL] $url -> $($_.Exception.Message)" -ForegroundColor Red
        return $null
    }
}

Write-Host "RFC 3161 国密 TSA 自测" -ForegroundColor Green
Write-Host "TSA Base : $TsaBase"
Write-Host "Demo Base: $DemoBase"

# 1. 健康检查
Write-Step 1 "健康检查 /health"
try {
    $health = Invoke-WebRequest -Uri "$TsaBase/health" -UseBasicParsing
    Write-Host "  status=$($health.StatusCode) body=$($health.Content.Trim())" -ForegroundColor Green
} catch {
    Write-Host "  [FAIL] $($_.Exception.Message)" -ForegroundColor Red
}

# 2. 服务信息
Write-Step 2 "服务信息 /info"
$info = Invoke-Safe "$TsaBase/info"
if ($info) { $info | ConvertTo-Json -Compress | Write-Host }

# 3. 下载证书
Write-Step 3 "下载 TSA/CA 证书"
try {
    Invoke-WebRequest -Uri "$TsaBase/tsa/cert" -OutFile "tsacert.pem" -UseBasicParsing
    Invoke-WebRequest -Uri "$TsaBase/tsa/cacert" -OutFile "cacert.pem" -UseBasicParsing
    Write-Host "  已保存 tsacert.pem / cacert.pem" -ForegroundColor Green
} catch {
    Write-Host "  [WARN] 证书下载失败: $($_.Exception.Message)" -ForegroundColor Yellow
}

# 4-8 依赖 Demo 应用
Write-Step 4 "SM3 摘要 (Demo)"
$sm3 = Invoke-Safe "$DemoBase/api/sm3/hash?text=Hello%20TSA"
if ($sm3) { $sm3 | ConvertTo-Json -Compress | Write-Host }

Write-Step 5 "SM2 密钥对 (Demo)"
$kp = Invoke-Safe "$DemoBase/api/sm2/keypair"
if ($kp) {
    Write-Host "  privateKeyHex length=$($kp.privateKeyHex.Length)"
    Write-Host "  publicKeyHex  length=$($kp.publicKeyHex.Length)"
}

Write-Step 6 "SM2 签名/验签 (Demo)"
$signBody = '{"text":"Hello, TSA!"}'
$sign = Invoke-Safe "$DemoBase/api/sm2/sign" "POST" $signBody
if ($sign -and $sign.signatureBase64 -and $sign.publicKeyHex) {
    $verifyBody = (@{
        text = "Hello, TSA!"
        signatureBase64 = $sign.signatureBase64
        publicKeyHex = $sign.publicKeyHex
    } | ConvertTo-Json)
    $verify = Invoke-Safe "$DemoBase/api/sm2/verify" "POST" $verifyBody
    if ($verify) { Write-Host "  verify.valid=$($verify.valid)" -ForegroundColor Green }
}

Write-Step 7 "SM2 加密/解密 (Demo)"
$enc = Invoke-Safe "$DemoBase/api/sm2/encrypt" "POST" '{"text":"Secret message"}'
if ($enc -and $enc.ciphertextBase64 -and $enc.privateKeyHex) {
    $decBody = (@{
        ciphertextBase64 = $enc.ciphertextBase64
        privateKeyHex = $enc.privateKeyHex
    } | ConvertTo-Json)
    $dec = Invoke-Safe "$DemoBase/api/sm2/decrypt" "POST" $decBody
    if ($dec) { Write-Host "  plaintext=$($dec.plaintext)" -ForegroundColor Green }
}

Write-Step 8 "TSA 时间戳请求 (Demo -> TSA Server)"
$ts = Invoke-Safe "$DemoBase/api/tsa/timestamp/text" "POST" '{"text":"Hello, TSA!"}'
if ($ts) {
    Write-Host "  success=$($ts.success) serial=$($ts.serialNumber) genTime=$($ts.genTime)" -ForegroundColor Green
    Write-Host "  policyOid=$($ts.policyOid) hashOid=$($ts.hashAlgorithmOid)"
}

Write-Host ""
Write-Host "=== 自测结束 ===" -ForegroundColor Green
