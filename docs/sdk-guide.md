# TSA Spring Boot Starter SDK 使用指南

## 1. 概述

TSA Spring Boot Starter 是一个基于 RFC 3161 时间戳协议的 Spring Boot Starter，提供国密 SM2/SM3 算法支持。

**主要功能：**

- 对数据请求 RFC 3161 时间戳（默认使用 SM3 摘要算法）
- 验证时间戳令牌（自动从 Token 提取证书，无需外部传入）
- 远端文件打时间戳/验证（GET 下载到本地临时目录，流式处理，处理后自动删除）
- SM2 密钥对生成、签名验证、加解密
- SM3 摘要计算

**Maven 依赖：**

```xml
<dependency>
    <groupId>com.shineyue.tsa</groupId>
    <artifactId>tsa-spring-boot-starter</artifactId>
    <version>1.0.5</version>
</dependency>
```

---

## 2. 快速开始

### 2.1 配置

SDK 默认不加载，仅需启用时配置 `enabled` 和 `url` 即可，其他配置项均有合理默认值，可根据实际情况按需调整。

**最小配置（启用 SDK）：**

```yaml
tsa:
  enabled: true                     # 启用 SDK
  url: http://your-tsa-server/tsa  # TSA 服务器地址（必须）
  gofastdfs-store: http://xxx.xxx.xx.xx:xxx  # gofastdfs-store电子档案访问程序地址
```

```properties
tsa.enabled=true
tsa.url=http://your-tsa-server/tsa
tsa.gofastdfs-store=http://xxx.xxx.xx.xx:xxx
```

**不使用 SDK 时：**

无需任何配置，或显式关闭：

```yaml
tsa:
  enabled: false   # 可选，不配置等同于此
```

**完整配置参考：**

```yaml
tsa:
  enabled: true                           # 是否启用 SDK（默认 false，需显式开启）
  url: http://your-tsa-server/tsa        # TSA 服务器地址（必须）
  connect-timeout: 5000                  # TCP 连接超时（毫秒），默认 5000
  read-timeout: 30000                    # HTTP 响应读取超时（毫秒），默认 30000
  policy-oid: 1.2.3.4.1                  # TSA 时间戳策略 OID，需与服务端配置一致
  cert-req: true                         # 是否要求 TSA 在响应中返回签名证书
  hash-algorithm: SM3                    # 摘要算法，默认 SM3（目前仅支持 SM3）
  auto-register-provider: true           # 是否自动注册 BouncyCastle Provider
  gofastdfs-store: http://xxx.xxx.xx.xx:xxx   # gofastdfs-store文件存储地址（无默认值，远端文件功能必须配置）
```

```properties
tsa.enabled=true
tsa.url=http://your-tsa-server/tsa
tsa.connect-timeout=5000
tsa.read-timeout=30000
tsa.policy-oid=1.2.3.4.1
tsa.cert-req=true
tsa.hash-algorithm=SM3
tsa.auto-register-provider=true
tsa.gofastdfs-store=http://xxx.xxx.xx.xx:xxx
```

> **关于 `gofastdfs-store`：** 该配置项无默认值，仅在调用 `timestampRemoteFile` / `verifyRemoteFile`
> 远端文件打戳/验戳功能时必须配置。实际下载地址 = `gofastdfs-store` 配置值 + 方法传入的 `filePath` 参数。
> 不使用远端文件功能时无需配置此项。

### 2.2 注入方式

推荐使用 `@Autowired(required = false)` 注入，确保 SDK 未启用时程序正常启动：

```java
@Autowired(required = false)
private TsaClient tsaClient;
```

### 2.3 基本使用

```java
import com.shineyue.tsa.TsaClient;
import com.shineyue.tsa.model.TimeStampResult;
import com.shineyue.tsa.model.TimeStampVerifyResult;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class YourService {

    @Autowired(required = false)
    private TsaClient tsaClient;

    // 对字符串打时间戳
    public void timestampText() {
        TimeStampResult result = tsaClient.timestamp("Hello, 国密时间戳!");

        if (result.isSuccess()) {
            // 成功: 持久化保存时间戳凭证
            String responseBase64 = result.getEncodedResponseBase64();
            System.out.println("序列号: " + result.getSerialNumberHex());
            System.out.println("生成时间: " + result.getGenTime());
            System.out.println("凭证 (Base64): " + responseBase64);
        } else {
            // 失败: 获取错误信息
            System.out.println("加戳失败: [" + result.getErrorCode() + "] " + result.getErrorMessage());
        }
    }

    // 验证时间戳
    public void verifyTimestamp() {
        String text = "Hello, 国密时间戳!";
        String responseBase64 = "..."; // 即加戳时保存的“凭证 (Base64)”

        TimeStampVerifyResult result = tsaClient.verifyTimestamp(text, responseBase64);

        if (result.isValid()) {
            // 验证通过：签名有效 且 摘要匹配
            System.out.println("验证通过");
            System.out.println("签名证书: " + result.getCertSubject());
        } else if (result.getErrorCode() != null) {
            // 验证过程出错（参数错误、解析失败、网络异常等）
            System.out.println("验证出错: [" + result.getErrorCode() + "] " + result.getErrorMessage());
        } else {
            // 验证结果不通过（签名无效或摘要不匹配）
            System.out.println("验证未通过");
            System.out.println("签名有效: " + result.isSignatureValid());
            System.out.println("摘要匹配: " + result.isHashMatch());
        }
    }
}
```

> **重要：** SDK 所有方法不再抛出异常，错误信息封装在结果对象中。
> - 加戳：`isSuccess()` 为 `false` 时通过 `getErrorCode()` + `getErrorMessage()` 获取失败原因
> - 验戳：`isValid()` 为 `false` 时需区分「过程出错」和「验证不通过」两种情况

### 2.4 大文件流式验证

对大文件打时间戳与验证都走 InputStream，全程无需把文件整体读入内存：

```java
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

// 1. 对大文件打时间戳（流式 SM3 摘要，低内存）
TimeStampResult result;
try (InputStream in = Files.newInputStream(Path.of("largefile.dat"))) {
    result = tsaClient.timestamp(in);
}

if (result.isSuccess()) {
    String responseBase64 = result.getEncodedResponseBase64();
    System.out.println("序列号: " + result.getSerialNumberHex());
    System.out.println("凭证: " + responseBase64);
} else {
    System.out.println("加戳失败: [" + result.getErrorCode() + "] " + result.getErrorMessage());
}

// 2. 日后验证：再次以流方式读同一文件，与凭证比对
try (InputStream in = Files.newInputStream(Path.of("largefile.dat"))) {
    TimeStampVerifyResult vr = tsaClient.verifyTimestamp(in, responseBase64);
    if (vr.isValid()) {
        System.out.println("验证通过，签名证书: " + vr.getCertSubject());
    } else if (vr.getErrorCode() != null) {
        System.out.println("验证出错: [" + vr.getErrorCode() + "] " + vr.getErrorMessage());
    } else {
        System.out.println("验证未通过，签名有效: " + vr.isSignatureValid() + ", 摘要匹配: " + vr.isHashMatch());
    }
}
```

### 2.5 远端文件打时间戳/验证

基于远端文件路径直接打时间戳/验证，无需手动下载文件。SDK 内部流程：

1. GET 请求下载：实际地址 = `tsa.gofastdfs-store` 配置值 + 传入的 `filePath` 参数
2. 下载流式写入当前程序运行目录下的 `tsa/` 子目录，不占用堆内存
3. 以文件流方式计算 SM3 摘要并请求/验证时间戳
4. 无论成功或失败，临时文件最终都会被自动删除

```java
// 前提: application.yml 已配置 tsa.gofastdfs-store

// 1. 对远端文件打时间戳
TimeStampResult result = tsaClient.timestampRemoteFile("/group1/default/document.pdf");

if (result.isSuccess()) {
    String responseBase64 = result.getEncodedResponseBase64();
    System.out.println("序列号: " + result.getSerialNumberHex());
    System.out.println("凭证: " + responseBase64);
} else {
    System.out.println("加戳失败: [" + result.getErrorCode() + "] " + result.getErrorMessage());
}

// 2. 日后验证：下载同一远端文件，与凭证比对
TimeStampVerifyResult vr = tsaClient.verifyRemoteFile("/group1/default/document.pdf", responseBase64);
if (vr.isValid()) {
    System.out.println("验证通过，签名证书: " + vr.getCertSubject());
} else if (vr.getErrorCode() != null) {
    System.out.println("验证出错: [" + vr.getErrorCode() + "] " + vr.getErrorMessage());
} else {
    System.out.println("验证未通过，签名有效: " + vr.isSignatureValid() + ", 摘要匹配: " + vr.isHashMatch());
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
| `timestampRemoteFile(String filePath)` | 远端文件路径 | `TimeStampResult` | **远端文件打时间戳**（GET 下载到本地临时目录，流式处理，自动删除） |

### 3.2 时间戳验证方法

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `verifyTimestamp(TimeStampResult result, X509Certificate tsaCert)` | 时间戳结果 + 外部证书 | `boolean` | 使用外部证书验证（传统方式） |
| `verifyTimestamp(byte[] data, byte[] responseDer)` | 原始数据 + DER 响应 | `TimeStampVerifyResult` | **自动提取证书验证**（推荐） |
| `verifyTimestamp(String text, String responseBase64)` | 文本 + Base64 响应 | `TimeStampVerifyResult` | **自动提取证书验证**（便捷方法） |
| `verifyTimestamp(InputStream inputStream, byte[] responseDer)` | 输入流 + DER 响应 | `TimeStampVerifyResult` | **流式验证**（大文件，低内存，不关闭流） |
| `verifyTimestamp(InputStream inputStream, String responseBase64)` | 输入流 + Base64 响应 | `TimeStampVerifyResult` | **流式验证**（便捷，大文件） |
| `verifyRemoteFile(String filePath, String responseBase64)` | 远端文件路径 + Base64 响应 | `TimeStampVerifyResult` | **远端文件验证**（GET 下载到本地临时目录，流式处理，自动删除） |

> **推荐用法：** 使用 `verifyTimestamp(String text, String responseBase64)` 或 `verifyTimestamp(byte[] data, byte[] responseDer)`，无需外部传入证书，自动从 Token 内嵌的 CMS SignedData 中提取签名者证书进行验证。支持证书轮换/续期场景。

> **错误处理：** 所有方法均不抛出异常，错误封装在结果对象中：
> - 加戳方法返回 `TimeStampResult`：`isSuccess()` 为 `false` 时通过 `getErrorCode()` / `getErrorMessage()` 获取失败原因
> - 验戳方法返回 `TimeStampVerifyResult`：`isValid()` 为 `false` 时检查 `getErrorCode()` 区分「过程出错」与「验证不通过」

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
| `success` | `boolean` | 是否成功（status=0/1 且无错误时为 true） |
| `errorCode` | `String` | 错误码（请求失败时填充，成功时为 null） |
| `errorMessage` | `String` | 错误消息（请求失败时填充，成功时为 null） |

**常用方法：**

```java
result.isSuccess();                   // 是否成功
result.getEncodedResponseBase64();    // 响应 Base64 编码
result.getTimeStampTokenBase64();     // Token Base64 编码
result.getSerialNumberHex();          // 序列号十六进制
result.getMessageImprintHex();        // 摘要十六进制
result.getErrorCode();                // 错误码（失败时）
result.getErrorMessage();             // 错误消息（失败时）
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
| `errorCode` | `String` | 错误码（验证过程出错时填充，正常时为 null） |
| `errorMessage` | `String` | 错误消息（验证过程出错时填充，正常时为 null） |

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
| `generateKeyPairBc()` | 生成 SM2 密钥对（BouncyCastle 底层 API） |
| `sign(byte[] data, PrivateKey privateKey)` | 签名（默认用户 ID） |
| `sign(byte[] data, PrivateKey privateKey, byte[] userId)` | 签名（指定用户 ID） |
| `signWithParams(byte[] data, ECPrivateKeyParameters privateKey, byte[] userId)` | 签名（BC 底层参数） |
| `verify(byte[] data, byte[] signature, PublicKey publicKey)` | 验证签名（默认用户 ID） |
| `verify(byte[] data, byte[] signature, PublicKey publicKey, byte[] userId)` | 验证签名（指定用户 ID） |
| `verifyWithParams(byte[] data, byte[] signature, ECPublicKeyParameters publicKey, byte[] userId)` | 验证签名（BC 底层参数） |
| `encrypt(byte[] plaintext, PublicKey publicKey)` | SM2 加密（C1C3C2） |
| `encryptBc(byte[] plaintext, ECPublicKeyParameters publicKey)` | SM2 加密（BC 底层参数） |
| `decrypt(byte[] ciphertext, PrivateKey privateKey)` | SM2 解密 |
| `decryptBc(byte[] ciphertext, ECPrivateKeyParameters privateKey)` | SM2 解密（BC 底层参数） |
| `privateKeyToHex(PrivateKey privateKey)` | 私钥转十六进制字符串（64 字符） |
| `publicKeyToHex(PublicKey publicKey)` | 公钥转十六进制（未压缩，130 字符，04 开头） |
| `publicKeyToHexCompressed(PublicKey publicKey)` | 公钥转十六进制（压缩，66 字符） |
| `privateKeyFromHex(String hex)` | 十六进制字符串恢复私钥 |
| `publicKeyFromHex(String hex)` | 十六进制字符串恢复公钥 |
| `getCurveSpec()` | 获取 SM2 曲线参数（sm2p256v1） |
| `toBcKeyParams(KeyPair keyPair)` | JCA KeyPair 转 BC 密钥参数 |

### 5.5 密钥序列化

```java
// 私钥 ↔ 十六进制
String privHex = Sm2Util.privateKeyToHex(keyPair.getPrivate());
PrivateKey restoredPriv = Sm2Util.privateKeyFromHex(privHex);

// 公钥 ↔ 十六进制（未压缩）
String pubHex = Sm2Util.publicKeyToHex(keyPair.getPublic());
PublicKey restoredPub = Sm2Util.publicKeyFromHex(pubHex);

// 压缩公钥（66 字符）
String pubHexCompressed = Sm2Util.publicKeyToHexCompressed(keyPair.getPublic());
```

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
| `hash(byte[] data, int offset, int length)` | 计算字节数组指定范围摘要 |
| `hash(String text)` | 计算字符串摘要（UTF-8） |
| `hash(InputStream inputStream)` | 计算流摘要（适合大文件，不关闭流） |
| `hashHex(byte[] data)` | 返回十六进制字符串 |
| `hashHex(String text)` | 返回十六进制字符串 |
| `hashHex(InputStream inputStream)` | 返回十六进制字符串 |
| `hashBase64(byte[] data)` | 返回 Base64 编码 |
| `hashBase64(String text)` | 返回 Base64 编码（字符串） |
| `newDigest()` | 获取 SM3Digest 实例（增量计算） |
| `finalizeDigest(SM3Digest digest)` | 完成增量摘要计算并返回 32 字节结果 |
| `getMessageDigest()` | 获取 JCE MessageDigest 实例（SM3，BC Provider） |
| `toHex(byte[] bytes)` | 字节数组转十六进制字符串（小写） |
| `fromHex(String hex)` | 十六进制字符串转字节数组 |

---

## 7. 配置属性参考 (TsaProperties)

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enabled` | boolean | `false` | **是否启用 SDK**，设为 `true` 才会创建 TsaClient Bean，不配置则 SDK 不加载 |
| `url` | String | `http://localhost:8080/tsa` | TSA 时间戳服务器地址，启用 SDK 时必须配置 |
| `connect-timeout` | int | `5000` | TCP 连接超时时间（毫秒），网络延迟较高时可适当加大 |
| `read-timeout` | int | `30000` | HTTP 响应读取超时时间（毫秒），大文件或慢网络时可适当加大 |
| `policy-oid` | String | `1.2.3.4.1` | TSA 时间戳策略 OID，需与服务端配置的 policy_oid 保持一致 |
| `cert-req` | boolean | `true` | 是否要求 TSA 在响应中返回签名证书，验证时间戳时需要用到 |
| `hash-algorithm` | String | `SM3` | 摘要算法，目前仅支持 SM3 |
| `auto-register-provider` | boolean | `true` | 是否自动注册 BouncyCastle Provider，已手动注册时可设为 `false` |
| `gofastdfs-store` | String | 无 | GoFastDFS 文件存储地址（IP+端口），**无默认值，使用远端文件功能时必须配置** |

> `gofastdfs-store` 用于 `timestampRemoteFile` / `verifyRemoteFile` 方法，
> 实际下载地址 = `gofastdfs-store` 配置值 + 方法传入的 `filePath` 参数（自动处理斜杠拼接与 URL 编码）。
> 未配置时调用远端文件方法将抛出 `TSA_CONFIG_MISSING` 异常。

---

## 8. 错误处理

SDK 所有方法均不抛出异常，错误信息封装在结果对象的 `errorCode` / `errorMessage` 字段中：

**加戳错误处理：**

```java
TimeStampResult result = tsaClient.timestamp("data");
if (!result.isSuccess()) {
    System.err.println("错误码: " + result.getErrorCode());
    System.err.println("错误信息: " + result.getErrorMessage());
}
```

**验戳错误处理：**

```java
TimeStampVerifyResult result = tsaClient.verifyTimestamp(text, responseBase64);
if (!result.isValid()) {
    if (result.getErrorCode() != null) {
        // 过程出错（参数错误、解析失败、网络异常等）
        System.err.println("错误码: " + result.getErrorCode());
        System.err.println("错误信息: " + result.getErrorMessage());
    } else {
        // 验证结果不通过（签名无效或摘要不匹配）
        System.out.println("签名有效: " + result.isSignatureValid());
        System.out.println("摘要匹配: " + result.isHashMatch());
    }
}
```

**常见错误码：**

| 错误码 | 说明 |
|--------|------|
| `TSA_DATA_NULL` | 输入数据为空（timestamp/verifyTimestamp 的 null 入参） |
| `TSA_HASH_EMPTY` | timestampWithHash 的预计算摘要为空 |
| `TSA_STREAM_ERROR` | timestamp(InputStream) 读取流失败 |
| `TSA_RESPONSE_NULL` | 时间戳响应数据为空 |
| `TSA_EMPTY_RESPONSE` | TSA 返回空响应体 |
| `TSA_HTTP_ERROR` | TSA 服务器返回非 200 状态 |
| `TSA_HTTP_IO` | HTTP 请求失败（网络异常） |
| `TSA_REJECTED` | TSA 拒绝请求（状态码非 0/1） |
| `TSA_NO_TOKEN` | 响应中无时间戳令牌 |
| `TSA_RESULT_NULL` | verifyTimestamp 的 result 或 token 为空 |
| `TSA_REQUEST_FAILED` | 时间戳请求过程异常（网络、解析等未分类错误） |
| `TSA_VERIFY_FAILED` | 时间戳验证过程异常 |
| `TSA_NO_SIGNER_CERT` | Token 中无签名者证书 |
| `TSA_CERT_LOAD` | loadCertificate 加载证书失败 |
| `TSA_CONFIG_MISSING` | 未配置 `tsa.gofastdfs-store` 却调用远端文件方法 |
| `TSA_DOWNLOAD_ERROR` | 远端文件下载失败（HTTP 非 200 或网络异常） |

> 内部流程也可能产生 `TSA_REQ_BUILD`（构造请求失败）、`TSA_PARSE_ERROR`（解析响应失败）等错误码，均封装在结果对象中。

---

## 9. 包结构

```
com.shineyue.tsa
├── TsaClient.java              # 核心客户端（时间戳请求、验证、远端文件打时间戳/验证）
├── TsaSigner.java              # RFC 3161 服务端签名器（SM3withSM2，供 tsa-server-java 使用）
├── TsaProperties.java          # 配置属性（含 tsa.gofastdfs-store 远端文件存储地址）
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
