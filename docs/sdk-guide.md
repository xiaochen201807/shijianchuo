# TSA Spring Boot Starter SDK 使用指南

## 1. 概述

TSA Spring Boot Starter 是一个基于 RFC 3161 时间戳协议的 Spring Boot Starter，提供国密 SM2/SM3 算法支持。

**主要功能：**

- 对数据请求 RFC 3161 时间戳（默认使用 SM3 摘要算法）
- 验证时间戳令牌（自动从 Token 提取证书，无需外部传入）
- SM2 密钥对生成、签名验证、加解密
- SM3 摘要计算

**Maven 依赖：**

```xml
<dependency>
    <groupId>com.shineyue.tsa</groupId>
    <artifactId>tsa-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

## 2. 快速开始

### 2.1 配置

在 `application.yml` 中添加配置：

```yaml
tsa:
  url: http://your-tsa-server/tsa    # TSA 服务器地址（必须）
  connect-timeout: 5000              # 连接超时（毫秒），默认 5000
  read-timeout: 30000                # 读取超时（毫秒），默认 30000
  policy-oid: 1.2.3.4.1              # TSA 策略 OID
  cert-req: true                     # 是否请求 TSA 证书
  hash-algorithm: SM3                # 摘要算法（默认 SM3）
  auto-register-provider: true       # 是否自动注册 BouncyCastle Provider
```

或使用 `application.properties` 格式：

```properties
tsa.url=http://your-tsa-server/tsa
tsa.connect-timeout=5000
tsa.read-timeout=30000
tsa.policy-oid=1.2.3.4.1
tsa.cert-req=true
tsa.hash-algorithm=SM3
tsa.auto-register-provider=true
```

### 2.2 基本使用

```java
import com.shineyue.tsa.TsaClient;
import com.shineyue.tsa.model.TimeStampResult;
import com.shineyue.tsa.model.TimeStampVerifyResult;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class YourService {

    @Autowired
    private TsaClient tsaClient;

    // 对字符串打时间戳
    public void timestampText() {
        TimeStampResult result = tsaClient.timestamp("Hello, 国密时间戳!");
        System.out.println("序列号: " + result.getSerialNumberHex());
        System.out.println("生成时间: " + result.getGenTime());
        System.out.println("Token (Base64): " + result.getTimeStampTokenBase64());
    }

    // 验证时间戳
    public void verifyTimestamp() {
        String text = "Hello, 国密时间戳!";
        String responseBase64 = "..."; // 之前获取的 responseBase64
        
        TimeStampVerifyResult result = tsaClient.verifyTimestamp(text, responseBase64);
        
        if (result.isValid()) {
            System.out.println("验证通过");
            System.out.println("签名证书: " + result.getCertSubject());
        } else {
            System.out.println("验证失败");
            System.out.println("签名有效: " + result.isSignatureValid());
            System.out.println("摘要匹配: " + result.isHashMatch());
        }
    }
}
```

---

## 3. TsaClient API 参考

### 3.1 时间戳请求方法

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `timestamp(byte[] data)` | 原始数据 | `TimeStampResult` | 对字节数据打时间戳（SM3 摘要） |
| `timestamp(String text)` | 文本字符串 | `TimeStampResult` | 对字符串打时间戳 |
| `timestamp(InputStream inputStream)` | 输入流 | `TimeStampResult` | 对流数据打时间戳（适合大文件） |
| `timestampWithHash(byte[] hash, ASN1ObjectIdentifier hashOid)` | 预计算摘要 + 算法 OID | `TimeStampResult` | 使用预先计算的摘要请求时间戳 |
| `timestampWithSm3Hash(byte[] sm3Hash)` | SM3 摘要值 | `TimeStampResult` | 使用 SM3 预计算摘要 |

### 3.2 时间戳验证方法

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `verifyTimestamp(TimeStampResult result, X509Certificate tsaCert)` | 时间戳结果 + 外部证书 | `boolean` | 使用外部证书验证（传统方式） |
| `verifyTimestamp(byte[] data, byte[] responseDer)` | 原始数据 + DER 响应 | `TimeStampVerifyResult` | **自动提取证书验证**（推荐） |
| `verifyTimestamp(String text, String responseBase64)` | 文本 + Base64 响应 | `TimeStampVerifyResult` | **自动提取证书验证**（便捷方法） |

> **推荐用法：** 使用 `verifyTimestamp(String text, String responseBase64)` 或 `verifyTimestamp(byte[] data, byte[] responseDer)`，无需外部传入证书，自动从 Token 内嵌的 CMS SignedData 中提取签名者证书进行验证。支持证书轮换/续期场景。

### 3.3 辅助方法

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `loadCertificate(InputStream certStream)` | 证书输入流 | `X509Certificate` | 从 PEM/DER 输入流加载证书 |
| `getProperties()` | - | `TsaProperties` | 获取配置属性 |

---

## 4. 数据模型

### 4.1 TimeStampResult

时间戳请求结果封装。

| 字段 | 类型 | 说明 |
|------|------|------|
| `encodedResponse` | `byte[]` | 原始时间戳响应（DER） |
| `timeStampToken` | `byte[]` | 时间戳令牌（CMS SignedData DER） |
| `serialNumber` | `BigInteger` | TSA 生成的序列号 |
| `genTime` | `Date` | 时间戳生成时间（UTC） |
| `policyOid` | `String` | 时间戳策略 OID |
| `hashAlgorithmOid` | `String` | 摘要算法 OID |
| `messageImprint` | `byte[]` | 原始摘要值 |
| `status` | `int` | 状态码（0=granted） |
| `statusString` | `String` | 状态描述 |

**常用方法：**

```java
result.getEncodedResponseBase64();    // 响应 Base64 编码
result.getTimeStampTokenBase64();     // Token Base64 编码
result.getSerialNumberHex();          // 序列号十六进制
result.getMessageImprintHex();        // 摘要十六进制
result.isSuccess();                   // 是否成功
```

### 4.2 TimeStampVerifyResult

时间戳验证结果封装。

| 字段 | 类型 | 说明 |
|------|------|------|
| `valid` | `boolean` | 验证是否通过（签名有效且摘要匹配） |
| `signatureValid` | `boolean` | 签名验证是否通过 |
| `hashMatch` | `boolean` | 摘要是否匹配 |
| `certSubject` | `String` | 签名证书主题 |
| `certExpiry` | `Date` | 签名证书过期时间 |
| `expectedHashHex` | `String` | 期望的摘要（十六进制） |
| `tokenHashHex` | `String` | Token 中的摘要（十六进制） |
| `serialNumber` | `String` | 时间戳序列号（十六进制） |
| `genTime` | `Date` | 时间戳生成时间 |
| `policyOid` | `String` | 时间戳策略 OID |

---

## 5. SM2 工具类 (Sm2Util)

SM2 国密非对称加密算法工具类，基于 BouncyCastle 实现。

### 5.1 密钥对生成

```java
import com.shineyue.tsa.sm2.Sm2Util;
import java.security.KeyPair;

// 生成 SM2 密钥对
KeyPair keyPair = Sm2Util.generateKeyPair();
PrivateKey privateKey = keyPair.getPrivate();
PublicKey publicKey = keyPair.getPublic();
```

### 5.2 数字签名

```java
// 签名（使用默认用户 ID）
byte[] data = "Hello, SM2!".getBytes();
byte[] signature = Sm2Util.sign(data, privateKey);

// 验证签名
boolean valid = Sm2Util.verify(data, signature, publicKey);
```

### 5.3 加解密

```java
// 加密
byte[] plaintext = "机密数据".getBytes();
byte[] ciphertext = Sm2Util.encrypt(plaintext, publicKey);

// 解密
byte[] decrypted = Sm2Util.decrypt(ciphertext, privateKey);
```

### 5.4 主要方法列表

| 方法 | 说明 |
|------|------|
| `generateKeyPair()` | 生成 SM2 密钥对（JCA） |
| `generateKeyPairBc()` | 生成 SM2 密钥对（BouncyCastle） |
| `sign(byte[] data, PrivateKey privateKey)` | 签名（默认用户 ID） |
| `sign(byte[] data, PrivateKey privateKey, byte[] userId)` | 签名（指定用户 ID） |
| `verify(byte[] data, byte[] signature, PublicKey publicKey)` | 验证签名 |
| `encrypt(byte[] plaintext, PublicKey publicKey)` | SM2 加密 |
| `decrypt(byte[] ciphertext, PrivateKey privateKey)` | SM2 解密 |

---

## 6. SM3 工具类 (Sm3Util)

SM3 国密摘要算法工具类，输出 256 位（32 字节）摘要值。

### 6.1 计算摘要

```java
import com.shineyue.tsa.sm3.Sm3Util;

// 计算字符串摘要（十六进制输出）
String hashHex = Sm3Util.hashHex("Hello, SM3!");
// 输出: 6e8b64a5f1c8d9e3...

// 计算字节数组摘要
byte[] hashBytes = Sm3Util.hash("Hello, SM3!".getBytes());

// 计算文件/流摘要
try (InputStream is = new FileInputStream("largefile.dat")) {
    String fileHash = Sm3Util.hashHex(is);
}
```

### 6.2 主要方法列表

| 方法 | 说明 |
|------|------|
| `hash(byte[] data)` | 计算字节数组摘要 |
| `hash(String text)` | 计算字符串摘要 |
| `hash(InputStream inputStream)` | 计算流摘要（适合大文件） |
| `hashHex(byte[] data)` | 返回十六进制字符串 |
| `hashHex(String text)` | 返回十六进制字符串 |
| `hashHex(InputStream inputStream)` | 返回十六进制字符串 |
| `hashBase64(byte[] data)` | 返回 Base64 编码 |
| `newDigest()` | 获取 SM3Digest 实例（增量计算） |

---

## 7. 配置属性参考 (TsaProperties)

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `url` | String | `http://localhost:8080/tsa` | TSA 服务器地址 |
| `connect-timeout` | int | 5000 | 连接超时（毫秒） |
| `read-timeout` | int | 30000 | 读取超时（毫秒） |
| `policy-oid` | String | `1.2.3.4.1` | TSA 策略 OID |
| `cert-req` | boolean | true | 是否请求 TSA 证书 |
| `hash-algorithm` | String | `SM3` | 摘要算法 |
| `auto-register-provider` | boolean | true | 是否自动注册 BC Provider |

---

## 8. 异常处理

SDK 抛出 `TsaException` 表示错误，包含错误码和错误信息：

```java
import com.shineyue.tsa.exception.TsaException;

try {
    TimeStampResult result = tsaClient.timestamp("data");
} catch (TsaException e) {
    System.err.println("错误码: " + e.getErrorCode());
    System.err.println("错误信息: " + e.getMessage());
}
```

**常见错误码：**

| 错误码 | 说明 |
|--------|------|
| `TSA_DATA_NULL` | 输入数据为空 |
| `TSA_HTTP_ERROR` | TSA 服务器返回非 200 状态 |
| `TSA_HTTP_IO` | HTTP 请求失败（网络异常） |
| `TSA_EMPTY_RESPONSE` | TSA 返回空响应 |
| `TSA_REJECTED` | TSA 拒绝请求 |
| `TSA_NO_TOKEN` | 响应中无时间戳令牌 |
| `TSA_VERIFY_FAILED` | 时间戳验证失败 |
| `TSA_NO_SIGNER_CERT` | Token 中无签名者证书 |
| `TSA_RESPONSE_NULL` | 响应数据为空 |

---

## 9. 包结构

```
com.shineyue.tsa
├── TsaClient.java              # 核心客户端（时间戳请求、验证）
├── TsaProperties.java          # 配置属性
├── TsaAutoConfiguration.java   # Spring Boot 自动配置
├── aot/
│   └── TsaRuntimeHints.java    # GraalVM Native Image 支持
├── exception/
│   └── TsaException.java       # 异常类
├── model/
│   ├── TimeStampResult.java        # 时间戳结果
│   └── TimeStampVerifyResult.java  # 验证结果
├── sm2/
│   └── Sm2Util.java            # SM2 工具类
└── sm3/
    └── Sm3Util.java            # SM3 工具类
```

---

## 10. 依赖要求

- Java 21+
- Spring Boot 3.5.x
- BouncyCastle 1.81（bcprov-jdk18on, bcpkix-jdk18on）

SDK 会自动注册 BouncyCastle Provider，无需手动配置。
