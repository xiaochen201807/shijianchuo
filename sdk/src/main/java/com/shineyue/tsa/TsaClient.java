package com.shineyue.tsa;

import com.shineyue.tsa.exception.TsaException;
import com.shineyue.tsa.model.TimeStampResult;
import com.shineyue.tsa.model.TimeStampVerifyResult;
import com.shineyue.tsa.sm3.Sm3Util;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * TSA 客户端 - RFC 3161 时间戳请求客户端
 *
 * 功能:
 *   1. 构造 RFC 3161 TimeStampReq 请求
 *   2. 通过 HTTP POST 发送请求到 TSA 服务器
 *   3. 解析 TimeStampResp 响应
 *   4. 验证时间戳令牌
 *
 * 默认使用国密 SM3 摘要算法
 *
 * 使用示例:
 *   TsaProperties props = new TsaProperties();
 *   props.setUrl("http://localhost:8080/tsa");
 *   TsaClient client = new TsaClient(props);
 *
 *   // 对数据打时间戳
 *   byte[] data = "Hello, TSA!".getBytes();
 *   TimeStampResult result = client.timestamp(data);
 *
 *   // 对文件打时间戳
 *   TimeStampResult fileResult = client.timestampFile(new File("document.pdf"));
 *
 *   // 验证时间戳
 *   boolean valid = client.verifyTimestamp(result, tsaCertificate);
 *
 *   // 对远端文件打时间戳/验证 (需配置 tsa.gofastdfs-store)
 *   props.setGofastdfsStore("http://192.168.1.10:8080");
 *   TimeStampResult remoteResult = client.timestampRemoteFile("/group1/default/document.pdf");
 *   TimeStampVerifyResult remoteVerify = client.verifyRemoteFile("/group1/default/document.pdf",
 *           remoteResult.getEncodedResponseBase64());
 */
public class TsaClient {

    private static final Logger logger = LoggerFactory.getLogger(TsaClient.class);

    /**
     * SM3 算法 OID (1.2.156.10197.1.401)
     */
    public static final ASN1ObjectIdentifier SM3_OID = new ASN1ObjectIdentifier("1.2.156.10197.1.401");

    /**
     * RFC 3161 时间戳请求 Content-Type
     */
    private static final String CONTENT_TYPE_QUERY = "application/timestamp-query";

    /**
     * RFC 3161 时间戳响应 Content-Type
     */
    private static final String CONTENT_TYPE_REPLY = "application/timestamp-reply";

    /**
     * 远端文件下载后本地临时目录名 (位于系统临时目录之下)
     */
    private static final String TEMP_DIR_NAME = "tsa";

    static {
        // 注册 BouncyCastle Provider
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final TsaProperties properties;

    /**
     * 构造 TSA 客户端
     *
     * @param properties 配置属性
     */
    public TsaClient(TsaProperties properties) {
        this.properties = properties;
        logger.info("TSA Client initialized: url={}, hashAlgorithm={}",
                properties.getUrl(), properties.getHashAlgorithm());
    }

    // ================================================================
    // 核心方法: 时间戳请求
    // ================================================================

    /**
     * 对原始数据请求时间戳 (使用 SM3 摘要)
     *
     * @param data 原始数据
     * @return 时间戳结果，请求过程出错时 isSuccess=false 且填充 errorCode/errorMessage
     */
    public TimeStampResult timestamp(byte[] data) {
        try {
            if (data == null) {
                return TimeStampResult.fail("TSA_DATA_NULL", "Input data cannot be null");
            }
            byte[] hash = Sm3Util.hash(data);
            return timestampWithHash(hash, SM3_OID);
        } catch (Exception e) {
            return TimeStampResult.fail("TSA_REQUEST_FAILED", "Timestamp request failed: " + e.getMessage());
        }
    }

    /**
     * 对字符串请求时间戳
     *
     * @param text 输入字符串
     * @return 时间戳结果，请求过程出错时 isSuccess=false 且填充 errorCode/errorMessage
     */
    public TimeStampResult timestamp(String text) {
        try {
            if (text == null) {
                return TimeStampResult.fail("TSA_DATA_NULL", "Input text cannot be null");
            }
            return timestamp(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return TimeStampResult.fail("TSA_REQUEST_FAILED", "Timestamp request failed: " + e.getMessage());
        }
    }

    /**
     * 对输入流数据请求时间戳 (流式处理，适合大文件)
     * 注意: 此方法会先计算整个流的 SM3 摘要
     *
     * @param inputStream 输入流
     * @return 时间戳结果，请求过程出错时 isSuccess=false 且填充 errorCode/errorMessage
     */
    public TimeStampResult timestamp(InputStream inputStream) {
        try {
            if (inputStream == null) {
                return TimeStampResult.fail("TSA_DATA_NULL", "Input stream cannot be null");
            }
            byte[] hash = Sm3Util.hash(inputStream);
            return timestampWithHash(hash, SM3_OID);
        } catch (Exception e) {
            return TimeStampResult.fail("TSA_REQUEST_FAILED", "Timestamp request failed: " + e.getMessage());
        }
    }

    /**
     * 使用预先计算的摘要请求时间戳
     * 适用于客户端已自行计算摘要的场景
     *
     * @param hash         预计算的摘要值
     * @param hashOid      摘要算法 OID (SM3: 1.2.156.10197.1.401)
     * @return 时间戳结果，请求过程出错时 isSuccess=false 且填充 errorCode/errorMessage
     */
    public TimeStampResult timestampWithHash(byte[] hash, ASN1ObjectIdentifier hashOid) {
        try {
            if (hash == null || hash.length == 0) {
                return TimeStampResult.fail("TSA_HASH_EMPTY", "Hash value cannot be null or empty");
            }

            logger.info("Sending timestamp request to TSA: url={}, hashAlgorithm={}",
                    properties.getUrl(), hashOid);

            byte[] requestDer = buildTimeStampRequest(hash, hashOid);
            byte[] responseDer = sendHttpPost(requestDer);
            return parseTimeStampResponse(responseDer, hash, hashOid, requestDer);
        } catch (Exception e) {
            return TimeStampResult.fail("TSA_REQUEST_FAILED", "Timestamp request failed: " + e.getMessage());
        }
    }

    /**
     * 使用 SM3 预计算摘要请求时间戳
     *
     * @param sm3Hash SM3 摘要值 (32 字节)
     * @return 时间戳结果，请求过程出错时 isSuccess=false 且填充 errorCode/errorMessage
     */
    public TimeStampResult timestampWithSm3Hash(byte[] sm3Hash) {
        try {
            return timestampWithHash(sm3Hash, SM3_OID);
        } catch (Exception e) {
            return TimeStampResult.fail("TSA_REQUEST_FAILED", "Timestamp request failed: " + e.getMessage());
        }
    }

    /**
     * 对远端文件请求时间戳 (流式处理)
     *
     * 处理流程:
     *   1. 通过 GET 请求从远端文件存储服务下载文件 (tsa.gofastdfs-store 配置值 + filePath)
     *   2. 下载过程流式写入当前程序运行目录下的 temp 子目录, 不占用堆内存
     *   3. 以文件流方式计算 SM3 摘要并请求时间戳
     *   4. 无论成功或失败, 最后都会删除本地临时文件
     *
     * @param filePath 远端文件路径 (拼接到 gofastdfs_store 之后作为完整下载地址)
     * @return 时间戳结果，请求过程出错时 isSuccess=false 且填充 errorCode/errorMessage
     */
    public TimeStampResult timestampRemoteFile(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return TimeStampResult.fail("TSA_DATA_NULL", "Remote file path cannot be null or empty");
        }

        Path tempFile = null;
        try {
            tempFile = downloadRemoteFile(filePath);
            try (InputStream in = Files.newInputStream(tempFile)) {
                byte[] hash = Sm3Util.hash(in);
                return timestampWithHash(hash, SM3_OID);
            }
        } catch (TsaException e) {
            return TimeStampResult.fail(e.getErrorCode(), e.getMessage());
        } catch (Exception e) {
            return TimeStampResult.fail("TSA_REQUEST_FAILED", "Timestamp request failed: " + e.getMessage());
        } finally {
            deleteQuietly(tempFile);
        }
    }

    // ================================================================
    // 验证方法
    // ================================================================

    /**
     * 验证时间戳令牌（需要外部提供 TSA 证书）
     *
     * @param result   时间戳结果
     * @param tsaCert  TSA 签名证书
     * @return true 如果验证通过
     * @throws TsaException 如果验证失败
     */
    public boolean verifyTimestamp(TimeStampResult result, X509Certificate tsaCert) throws TsaException {
        if (result == null || result.getTimeStampToken() == null) {
            throw new TsaException("TSA_RESULT_NULL", "Time stamp result or token is null");
        }

        try {
            CMSSignedData cmsData = new CMSSignedData(result.getTimeStampToken());
            TimeStampToken token = new TimeStampToken(cmsData);

            JcaSimpleSignerInfoVerifierBuilder verifierBuilder = new JcaSimpleSignerInfoVerifierBuilder();
            verifierBuilder.setProvider("BC");
            token.validate(verifierBuilder.build(tsaCert));

            logger.info("Timestamp token verified successfully. Serial: {}, GenTime: {}",
                    token.getTimeStampInfo().getSerialNumber(), token.getTimeStampInfo().getGenTime());

            return true;

        } catch (Exception e) {
            logger.error("Timestamp verification failed", e);
            throw new TsaException("TSA_VERIFY_FAILED", "Timestamp verification failed", e);
        }
    }

    /**
     * 验证时间戳令牌（自动从 Token 内部提取签名证书）
     *
     * 无需外部传入证书，自动从 Token 的 CMS SignedData 中提取签名者证书进行验证。
     * 支持证书轮换/续期场景，因为验证使用的是 Token 内嵌的证书。
     *
     * @param data 原始数据（用于验证摘要是否匹配）
     * @param responseDer 时间戳响应 DER 编码数据
     * @return 验证结果，包含签名有效性、摘要匹配、证书信息等，验证过程出错时 valid=false 且填充 errorCode/errorMessage
     */
    public TimeStampVerifyResult verifyTimestamp(byte[] data, byte[] responseDer) {
        try {
            if (data == null) {
                return TimeStampVerifyResult.fail("TSA_DATA_NULL", "Input data cannot be null");
            }
            byte[] expectedHash = Sm3Util.hash(data);
            return verifyWithHash(expectedHash, responseDer);
        } catch (Exception e) {
            return TimeStampVerifyResult.fail("TSA_VERIFY_FAILED", "Timestamp verification failed: " + e.getMessage());
        }
    }

    /**
     * 验证时间戳令牌（输入流数据 + DER 编码的响应）
     *
     * 流式计算 SM3 摘要，无需把大文件整体读入内存，适合对大文件做时间戳验证。
     * 注意：本方法不会关闭传入的 InputStream，由调用方负责资源管理。
     *
     * @param inputStream 原始数据输入流
     * @param responseDer 时间戳响应 DER 编码数据
     * @return 验证结果，包含签名有效性、摘要匹配、证书信息等，验证过程出错时 valid=false 且填充 errorCode/errorMessage
     */
    public TimeStampVerifyResult verifyTimestamp(InputStream inputStream, byte[] responseDer) {
        try {
            if (inputStream == null) {
                return TimeStampVerifyResult.fail("TSA_DATA_NULL", "Input stream cannot be null");
            }
            byte[] expectedHash = Sm3Util.hash(inputStream);
            return verifyWithHash(expectedHash, responseDer);
        } catch (Exception e) {
            return TimeStampVerifyResult.fail("TSA_VERIFY_FAILED", "Timestamp verification failed: " + e.getMessage());
        }
    }

    /**
     * 验证时间戳令牌（字符串数据 + Base64 编码的响应）
     *
     * @param text 原始文本
     * @param responseBase64 Base64 编码的时间戳响应
     * @return 验证结果，验证过程出错时 valid=false 且填充 errorCode/errorMessage
     */
    public TimeStampVerifyResult verifyTimestamp(String text, String responseBase64) {
        try {
            if (text == null) {
                return TimeStampVerifyResult.fail("TSA_DATA_NULL", "Input text cannot be null");
            }
            if (responseBase64 == null || responseBase64.isEmpty()) {
                return TimeStampVerifyResult.fail("TSA_RESPONSE_NULL", "Response Base64 cannot be null or empty");
            }
            byte[] expectedHash = Sm3Util.hash(text.getBytes(StandardCharsets.UTF_8));
            byte[] responseDer = Base64.getDecoder().decode(responseBase64);
            return verifyWithHash(expectedHash, responseDer);
        } catch (Exception e) {
            return TimeStampVerifyResult.fail("TSA_VERIFY_FAILED", "Timestamp verification failed: " + e.getMessage());
        }
    }

    /**
     * 验证时间戳令牌（输入流数据 + Base64 编码的响应）
     *
     * 流式计算 SM3 摘要，适合对大文件做时间戳验证。本方法不会关闭传入的 InputStream。
     *
     * @param inputStream 原始数据输入流
     * @param responseBase64 Base64 编码的时间戳响应
     * @return 验证结果，验证过程出错时 valid=false 且填充 errorCode/errorMessage
     */
    public TimeStampVerifyResult verifyTimestamp(InputStream inputStream, String responseBase64) {
        try {
            if (responseBase64 == null || responseBase64.isEmpty()) {
                return TimeStampVerifyResult.fail("TSA_RESPONSE_NULL", "Response Base64 cannot be null or empty");
            }
            byte[] responseDer = Base64.getDecoder().decode(responseBase64);
            return verifyTimestamp(inputStream, responseDer);
        } catch (Exception e) {
            return TimeStampVerifyResult.fail("TSA_VERIFY_FAILED", "Timestamp verification failed: " + e.getMessage());
        }
    }

    /**
     * 验证远端文件的时间戳令牌 (流式处理)
     *
     * 处理流程:
     *   1. 通过 GET 请求从远端文件存储服务下载文件 (tsa.gofastdfs-store 配置值 + filePath)
     *   2. 下载过程流式写入当前程序运行目录下的 temp 子目录, 不占用堆内存
     *   3. 以文件流方式计算 SM3 摘要, 与 Base64 凭证做完整验证
     *   4. 无论成功或失败, 最后都会删除本地临时文件
     *
     * @param filePath       远端文件路径 (拼接到 gofastdfs_store 之后作为完整下载地址)
     * @param responseBase64 Base64 编码的时间戳响应 (timestampRemoteFile 返回的 encodedResponseBase64)
     * @return 验证结果，验证过程出错时 valid=false 且填充 errorCode/errorMessage
     */
    public TimeStampVerifyResult verifyRemoteFile(String filePath, String responseBase64) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return TimeStampVerifyResult.fail("TSA_DATA_NULL", "Remote file path cannot be null or empty");
        }
        if (responseBase64 == null || responseBase64.isEmpty()) {
            return TimeStampVerifyResult.fail("TSA_RESPONSE_NULL", "Response Base64 cannot be null or empty");
        }

        Path tempFile = null;
        try {
            tempFile = downloadRemoteFile(filePath);
            try (InputStream in = Files.newInputStream(tempFile)) {
                byte[] hash = Sm3Util.hash(in);
                byte[] responseDer = Base64.getDecoder().decode(responseBase64);
                return verifyWithHash(hash, responseDer);
            }
        } catch (TsaException e) {
            return TimeStampVerifyResult.fail(e.getErrorCode(), e.getMessage());
        } catch (Exception e) {
            return TimeStampVerifyResult.fail("TSA_VERIFY_FAILED", "Timestamp verification failed: " + e.getMessage());
        } finally {
            deleteQuietly(tempFile);
        }
    }

    /**
     * 核心验证逻辑：用预先计算的摘要 + 时间戳响应做完整验证。
     * 解析响应、检查状态、提取 Token 内嵌证书验签、比对摘要，构建结果。
     * 所有公开 verifyTimestamp(InputStream/byte[]/String, ...) 重载最终都委托到这里。
     *
     * @param expectedHash 原始数据已计算出的 SM3 摘要
     * @param responseDer 时间戳响应 DER 编码数据
     * @return 验证结果，验证过程出错时 valid=false 且填充 errorCode/errorMessage
     */
    private TimeStampVerifyResult verifyWithHash(byte[] expectedHash, byte[] responseDer) {
        if (expectedHash == null || expectedHash.length == 0) {
            return TimeStampVerifyResult.fail("TSA_HASH_EMPTY", "Hash value cannot be null or empty");
        }
        if (responseDer == null || responseDer.length == 0) {
            return TimeStampVerifyResult.fail("TSA_RESPONSE_NULL", "Timestamp response data cannot be null or empty");
        }

        try {
            // 1. 解析时间戳响应
            TimeStampResponse tsResponse = new TimeStampResponse(responseDer);

            // 2. 检查响应状态
            int status = tsResponse.getStatus();
            if (status != 0 && status != 1) {
                return TimeStampVerifyResult.fail("TSA_REJECTED",
                        "TSA response status: " + status + " - " + tsResponse.getStatusString());
            }

            TimeStampToken token = tsResponse.getTimeStampToken();
            if (token == null) {
                return TimeStampVerifyResult.fail("TSA_NO_TOKEN", "No timestamp token in response");
            }

            // 3. 从 Token 内部提取签名证书
            CMSSignedData cmsData = new CMSSignedData(token.getEncoded());
            SignerInformation signerInfo = cmsData.getSignerInfos().getSigners().iterator().next();

            X509Certificate embeddedCert = null;
            for (Object certObj : cmsData.getCertificates().getMatches(signerInfo.getSID())) {
                if (certObj instanceof X509CertificateHolder) {
                    embeddedCert = new JcaX509CertificateConverter()
                            .setProvider("BC")
                            .getCertificate((X509CertificateHolder) certObj);
                    break;
                }
            }

            if (embeddedCert == null) {
                return TimeStampVerifyResult.fail("TSA_NO_SIGNER_CERT", "No signer certificate found in token");
            }

            // 4. 用 Token 内嵌证书验证签名
            boolean signatureValid;
            try {
                JcaSimpleSignerInfoVerifierBuilder verifierBuilder = new JcaSimpleSignerInfoVerifierBuilder();
                verifierBuilder.setProvider("BC");
                token.validate(verifierBuilder.build(embeddedCert));
                signatureValid = true;
            } catch (Exception e) {
                logger.warn("Token signature validation failed", e);
                signatureValid = false;
            }

            // 5. 验证摘要是否匹配原始数据
            byte[] tokenHash = token.getTimeStampInfo().getMessageImprintDigest();
            boolean hashMatch = Arrays.equals(expectedHash, tokenHash);

            // 6. 提取证书信息
            String certSubject = embeddedCert.getSubjectX500Principal().getName();
            Date certExpiry = embeddedCert.getNotAfter();

            // 7. 构建验证结果
            return new TimeStampVerifyResult(
                    signatureValid && hashMatch,
                    signatureValid,
                    hashMatch,
                    certSubject,
                    certExpiry,
                    Sm3Util.toHex(expectedHash),
                    Sm3Util.toHex(tokenHash),
                    token.getTimeStampInfo().getSerialNumber().toString(16),
                    token.getTimeStampInfo().getGenTime(),
                    token.getTimeStampInfo().getPolicy() != null ? token.getTimeStampInfo().getPolicy().getId() : null
            );

        } catch (Exception e) {
            logger.error("Timestamp verification failed", e);
            return TimeStampVerifyResult.fail("TSA_VERIFY_FAILED", "Timestamp verification failed: " + e.getMessage());
        }
    }

    /**
     * 从 PEM 输入流加载 X.509 证书
     *
     * @param certStream 证书输入流 (PEM 或 DER)
     * @return X509 证书
     * @throws TsaException 如果加载失败
     */
    public X509Certificate loadCertificate(InputStream certStream) throws TsaException {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509", "BC");
            return (X509Certificate) factory.generateCertificate(certStream);
        } catch (Exception e) {
            throw new TsaException("TSA_CERT_LOAD", "Failed to load certificate", e);
        }
    }

    // ================================================================
    // 内部方法
    // ================================================================

    /**
     * 校验远端文件存储配置是否可用
     *
     * @throws TsaException 如果 tsa.gofastdfs-store 未配置
     */
    private void assertRemoteStoreConfigured() throws TsaException {
        if (properties.getGofastdfsStore() == null
                || properties.getGofastdfsStore().trim().isEmpty()) {
            throw new TsaException("TSA_CONFIG_MISSING",
                    "Remote file store is not configured (tsa.gofastdfs-store)");
        }
    }

    /**
     * 通过 GET 请求下载远端文件到本地临时目录 (流式写入, 不占内存)
     *
     * 下载地址 = tsa.gofastdfs-store 配置值 + filePath
     * 临时目录 = 当前程序运行目录下的 temp/
     *
     * @param filePath 远端文件路径
     * @return 已下载完成的本地临时文件路径 (由调用方负责删除)
     * @throws TsaException 如果配置缺失或下载失败
     */
    private Path downloadRemoteFile(String filePath) throws TsaException {
        assertRemoteStoreConfigured();

        // 1. 调用 transform 接口将逻辑路径转化为实际存储路径
        String resolvedPath = resolveTransformedPath(filePath);
        logger.info("Resolved actual path: {}", resolvedPath);

        // 2. 用转化后的路径构建下载地址
        // 只对路径部分做 percent-encode (base URL 包含 :// 和端口不能编码)
        // GoFastDFS 不认 %40, 但 Tomcat 拒绝裸非 ASCII 和 URL 保留字符
        String base = properties.getGofastdfsStore();
        String encodedPath = encodePath(resolvedPath);
        HttpURLConnection connection = null;
        Path tempFile = null;
        String fullUrl = base + encodedPath;
        logger.info("Downloading remote file: {} (original: {})", fullUrl, filePath);
        try {
            // 用 URL 四参数构造器直接存储已编码路径, 绕过 URI.create()/parseURL()
            // 避免 GraalVM Native Image 的 HttpURLConnection 对 % 做二次编码
            @SuppressWarnings("deprecation")
            URL baseUrl = new URL(base);
            @SuppressWarnings("deprecation")
            URL url = new URL(baseUrl.getProtocol(), baseUrl.getHost(),
                    baseUrl.getPort(), encodedPath);

            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(properties.getConnectTimeout());
            connection.setReadTimeout(properties.getReadTimeout());
            connection.setDoInput(true);

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                // 错误流必须读完才能归还连接
                byte[] errorBytes = readStream(connection.getErrorStream());
                String errorBody = errorBytes != null ? new String(errorBytes, StandardCharsets.UTF_8) : "";
                logger.error("[DOWNLOAD-DIAG] HTTP={} | runtime={} | resolvedPath={} | encodedPath={} | url.getFile()={} | url.toExternalForm()={} | responseBody={}",
                        responseCode, System.getProperty("java.vm.name"),
                        resolvedPath, encodedPath, url.getFile(), url.toExternalForm(), errorBody);
                throw new TsaException("TSA_DOWNLOAD_ERROR",
                        "Download remote file failed, HTTP " + responseCode + ": " + errorBody);
            }

            // 确保本地临时目录存在 (当前程序运行目录下的 temp 子目录)
            Path tempDir = Paths.get(TEMP_DIR_NAME);
            Files.createDirectories(tempDir);
            tempFile = tempDir.resolve(buildTempFileName(resolvedPath));
            // 流式下载写入本地临时文件
            try (InputStream in = connection.getInputStream()) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            logger.info("Remote file downloaded to temp: {} ({} bytes)", tempFile, Files.size(tempFile));
            return tempFile;

        } catch (TsaException e) {
            deleteQuietly(tempFile);
            throw e;
        } catch (IOException | IllegalArgumentException e) {
            // 下载失败清理半成品临时文件并丢弃坏连接
            logger.error("[DOWNLOAD-DIAG] EXCEPTION | runtime={} | resolvedPath={} | encodedPath={} | fullUrl={} | exType={} | exMsg={}",
                    System.getProperty("java.vm.name"), resolvedPath, encodedPath, fullUrl,
                    e.getClass().getName(), e.getMessage(), e);
            deleteQuietly(tempFile);
            if (connection != null) {
                connection.disconnect();
            }
            throw new TsaException("TSA_DOWNLOAD_ERROR",
                    "Failed to download remote file: " + fullUrl
                            + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")", e);
        }
    }

    /**
     * 调用 GoFastDFS transform 接口，将逻辑文件路径转化为实际存储路径
     *
     * 请求: POST {gofastdfs-store}/group1/transform/filePath
     *       Content-Type: application/json
     *       Body: {"path": "/storeIdentity/xxx/file.jpg"}
     * 响应 JSON: {"code": 0, "data": "/group1/@xxx/actual/path.jpg", "msg": "转化成功"}
     *
     * 当 code == 0 时取 data 字段作为实际路径，否则抛出异常
     *
     * @param filePath 原始逻辑文件路径
     * @return 转化后的实际存储路径
     * @throws TsaException 如果转化接口调用失败或返回非 0 code
     */
    private String resolveTransformedPath(String filePath) throws TsaException {
        String transformUrl = buildTransformUrl();
        logger.info("Calling transform API: {}", transformUrl);

        HttpURLConnection connection = null;
        try {
            URL url = URI.create(transformUrl).toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setConnectTimeout(properties.getConnectTimeout());
            connection.setReadTimeout(properties.getReadTimeout());
            connection.setDoOutput(true);
            connection.setDoInput(true);

            // 构造 JSON 请求体: {"path": "filePath"}
            String requestBody = "{\"path\":\"" + escapeJsonString(filePath) + "\"}";
            try (OutputStream os = connection.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                byte[] errorBytes = readStream(connection.getErrorStream());
                String errorBody = errorBytes != null ? new String(errorBytes, StandardCharsets.UTF_8) : "";
                throw new TsaException("TSA_TRANSFORM_ERROR",
                        "Transform API failed, HTTP " + responseCode + ": " + errorBody);
            }

            // 读取 JSON 响应
            byte[] responseBytes;
            try (InputStream in = connection.getInputStream()) {
                responseBytes = readStream(in);
            }
            String json = new String(responseBytes, StandardCharsets.UTF_8);
            logger.debug("Transform API response: {}", json);

            // 解析 code 字段: 检查是否为 0 (成功)
            if (!json.contains("\"code\":0") && !json.contains("\"code\": 0")) {
                // 尝试提取 msg 用于错误信息
                String msg = extractJsonStringField(json, "msg");
                throw new TsaException("TSA_TRANSFORM_ERROR",
                        "Transform API returned error: " + (msg != null ? msg : json));
            }

            // 提取 data 字段 (转化后的实际路径)
            String data = extractJsonStringField(json, "data");
            if (data == null || data.isEmpty()) {
                throw new TsaException("TSA_TRANSFORM_ERROR",
                        "Transform API returned empty data field");
            }

            logger.info("Transformed path: {} -> {}", filePath, data);
            return data;

        } catch (TsaException e) {
            throw e;
        } catch (IOException | IllegalArgumentException e) {
            if (connection != null) {
                connection.disconnect();
            }
            throw new TsaException("TSA_TRANSFORM_ERROR",
                    "Failed to call transform API for: " + filePath, e);
        }
    }

    /**
     * 拼接 transform 接口地址: gofastdfs_store + /group1/transform/filePath
     */
    private String buildTransformUrl() {
        String base = properties.getGofastdfsStore();
        StringBuilder url = new StringBuilder(base);
        if (!base.endsWith("/")) {
            url.append('/');
        }
        url.append("group1/transform/filePath");
        return url.toString();
    }

    /**
     * 转义 JSON 字符串值中的特殊字符 (双引号、反斜杠、控制字符)
     * 确保 filePath 嵌入 JSON 值时不会破坏结构
     */
    private String escapeJsonString(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 从 JSON 字符串中提取指定 key 的字符串值 (轻量解析，无第三方 JSON 库依赖)
     * 仅支持简单 JSON 对象中顶层字符串字段提取
     *
     * @param json JSON 字符串
     * @param key  要提取的字段名
     * @return 字段值，未找到返回 null
     */
    private String extractJsonStringField(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int searchFrom = 0;
        while (searchFrom < json.length()) {
            int keyIdx = json.indexOf(searchKey, searchFrom);
            if (keyIdx < 0) {
                return null;
            }
            // 确认后面紧跟 ':' (跳过空白), 确保匹配的是 JSON key 而非其他字段的子串
            // 例如搜索 "data" 不会误匹配到 "datas"
            int afterKey = keyIdx + searchKey.length();
            while (afterKey < json.length() && (json.charAt(afterKey) == ' ' || json.charAt(afterKey) == '\t')) {
                afterKey++;
            }
            if (afterKey < json.length() && json.charAt(afterKey) == ':') {
                int startQuote = json.indexOf('"', afterKey + 1);
                if (startQuote < 0) {
                    return null;
                }
                int endQuote = json.indexOf('"', startQuote + 1);
                if (endQuote < 0) {
                    return null;
                }
                return json.substring(startQuote + 1, endQuote);
            }
            searchFrom = keyIdx + 1;
        }
        return null;
    }

    /**
     * 对 URL 路径做选择性 percent-encode
     *
     * 保留安全的字符不编码:
     *   - 字母数字 (a-z A-Z 0-9)
     *   - RFC 3986 unreserved: - _ . ~
     *   - / (路径分隔符)
     *   - @ (GoFastDFS 不认 %40, 必须保持原样)
     *
     * 其他所有字符均做 UTF-8 percent-encode, 包括:
     *   - 非 ASCII 字符 (中文等) — Tomcat 拒绝裸非 ASCII
     *   - URL 保留字符 (? # { } % 空格 等) — Tomcat 视为请求行分隔符
     */
    private String encodePath(String value) {
        StringBuilder sb = new StringBuilder(value.length() * 2);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isPathSafe(c)) {
                sb.append(c);
            } else {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    sb.append('%');
                    sb.append(String.format("%02X", b & 0xFF));
                }
            }
        }
        return sb.toString();
    }

    /**
     * 判断字符是否为 URL 路径安全字符 (不需编码)
     */
    private boolean isPathSafe(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.' || c == '~'
                || c == '/'
                || c == '@';
    }

    /**
     * 生成临时文件名: tsa-<UUID>-<原始文件名>
     *
     * 保留原始文件名便于排查, 同时清理 Windows 非法文件名字符避免落盘失败
     */
    private String buildTempFileName(String filePath) {
        String name = filePath;
        int idx = filePath.lastIndexOf('/');
        if (idx >= 0) {
            name = filePath.substring(idx + 1);
        }
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.isEmpty()) {
            name = "download";
        }
        return "tsa-" + UUID.randomUUID() + "-" + name;
    }

    /**
     * 静默删除本地临时文件, 删除失败仅记录告警日志
     */
    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            logger.warn("Failed to delete temp file: {}", path, e);
        }
    }

    /**
     * 构建 RFC 3161 TimeStampReq 请求
     */
    private byte[] buildTimeStampRequest(byte[] hash, ASN1ObjectIdentifier hashOid) throws TsaException {
        try {
            TimeStampRequestGenerator generator = new TimeStampRequestGenerator();

            // 设置 TSA 策略
            if (properties.getPolicyOid() != null && !properties.getPolicyOid().isEmpty()) {
                generator.setReqPolicy(new ASN1ObjectIdentifier(properties.getPolicyOid()));
            }

            // 设置是否请求证书
            generator.setCertReq(properties.isCertReq());

            // 生成 nonce，防止重放攻击 (RFC 3161 推荐)
            BigInteger nonce = new BigInteger(64, new java.security.SecureRandom());

            // 生成请求 (使用指定的摘要算法 + nonce)
            TimeStampRequest request = generator.generate(hashOid, hash, nonce);

            byte[] encoded = request.getEncoded();
            logger.debug("TimeStampReq generated: nonce={}, policy={}",
                    request.getNonce(), request.getReqPolicy());

            return encoded;

        } catch (IOException e) {
            throw new TsaException("TSA_REQ_BUILD", "Failed to build TimeStampReq", e);
        }
    }

    /**
     * 发送 HTTP POST 请求到 TSA 服务器
     *
     * 设计说明:
     * - 使用 HTTP/1.1 默认 keepalive (不发送 Connection: close)
     * - 不调用 disconnect(), 让连接进入 JVM KeepAliveCache 被后续请求复用
     * - 必须完整读取响应体 + 关闭 InputStream, HttpURLConnection 才会归还连接到缓存池
     * - 这样避免每次请求消耗临时端口, 解决高并发下 BindException: Cannot assign requested address
     */
    private byte[] sendHttpPost(byte[] requestBody) throws TsaException {
        HttpURLConnection connection = null;
        InputStream responseStream = null;
        try {
            URL url = URI.create(properties.getUrl()).toURL();
            connection = (HttpURLConnection) url.openConnection();

            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", CONTENT_TYPE_QUERY);
            connection.setRequestProperty("Accept", CONTENT_TYPE_REPLY);
            // 注意: 不设置 Connection: close —— 让 HttpURLConnection 复用连接
            // JVM KeepAliveCache 会自动管理连接池, 默认 keepalive 5s, 缓存 5 条/目标

            connection.setConnectTimeout(properties.getConnectTimeout());
            connection.setReadTimeout(properties.getReadTimeout());
            connection.setDoOutput(true);
            connection.setDoInput(true);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(requestBody);
                os.flush();
            }

            int responseCode = connection.getResponseCode();

            if (responseCode != 200) {
                // 错误流必须读完才能归还连接
                byte[] errorBytes = readStream(connection.getErrorStream());
                String errorBody = errorBytes != null ? new String(errorBytes, StandardCharsets.UTF_8) : "";
                throw new TsaException("TSA_HTTP_ERROR",
                        "TSA server returned HTTP " + responseCode + ": " + errorBody);
            }

            String contentType = connection.getContentType();
            if (contentType != null && !contentType.contains(CONTENT_TYPE_REPLY)) {
                logger.warn("Unexpected Content-Type: {}", contentType);
            }

            responseStream = connection.getInputStream();
            byte[] responseBody = readStream(responseStream);
            if (responseBody == null || responseBody.length == 0) {
                throw new TsaException("TSA_EMPTY_RESPONSE", "TSA server returned empty response");
            }

            return responseBody;

        } catch (IOException e) {
            // 异常时强制丢弃连接, 不复用
            if (connection != null) {
                connection.disconnect();
            }
            throw new TsaException("TSA_HTTP_IO", "HTTP request to TSA failed", e);
        } finally {
            // 必须关闭 InputStream, HttpURLConnection 才会把连接归还到 KeepAliveCache
            if (responseStream != null) {
                try {
                    responseStream.close();
                } catch (IOException ignored) {
                }
            }
            // 注意: 正常情况 NOT 调用 disconnect() —— 让连接留在缓存池中被复用
            // 只有异常情况才 disconnect() 丢弃坏连接
        }
    }

    /**
     * 解析时间戳响应
     */
    private TimeStampResult parseTimeStampResponse(byte[] responseDer,
                                                    byte[] hash,
                                                    ASN1ObjectIdentifier hashOid,
                                                    byte[] requestDer) throws TsaException {
        try {
            // 解析 TimeStampResponse
            TimeStampResponse response = new TimeStampResponse(responseDer);

            // 验证响应状态
            if (response.getStatus() != 0 && response.getStatus() != 1) {
                String statusStr = response.getStatusString();
                throw new TsaException("TSA_REJECTED",
                        "TSA rejected request. Status: " + response.getStatus() +
                                (statusStr != null ? ", Message: " + statusStr : ""));
            }

            // 获取时间戳令牌
            TimeStampToken token = response.getTimeStampToken();
            if (token == null) {
                throw new TsaException("TSA_NO_TOKEN", "TSA response contains no time stamp token");
            }

            // 验证响应与请求匹配
            TimeStampRequest request = new TimeStampRequest(requestDer);
            response.validate(request);

            // 提取信息
            org.bouncycastle.tsp.TimeStampTokenInfo tokenInfo = token.getTimeStampInfo();

            BigInteger serialNumber = tokenInfo.getSerialNumber();
            Date genTime = tokenInfo.getGenTime();
            String policyOid = tokenInfo.getPolicy() != null ? tokenInfo.getPolicy().getId() : null;
            String algOid = tokenInfo.getHashAlgorithm() != null ? tokenInfo.getHashAlgorithm().getAlgorithm().getId() : null;
            byte[] messageImprint = tokenInfo.getMessageImprintDigest();

            // 获取时间戳令牌 DER 编码
            byte[] tokenDer = token.getEncoded();

            logger.info("Timestamp received: serial={}, genTime={}, policy={}",
                    serialNumber.toString(16), genTime, policyOid);

            return new TimeStampResult(
                    responseDer,
                    tokenDer,
                    serialNumber,
                    genTime,
                    policyOid,
                    algOid,
                    messageImprint,
                    response.getStatus(),
                    response.getStatusString()
            );

        } catch (TsaException e) {
            throw e;
        } catch (Exception e) {
            throw new TsaException("TSA_PARSE_ERROR", "Failed to parse TimeStampResp", e);
        }
    }

    /**
     * 读取输入流到字节数组
     */
    private byte[] readStream(InputStream is) throws IOException {
        if (is == null) {
            return new byte[0];
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[4096];
        int nRead;
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        buffer.flush();
        return buffer.toByteArray();
    }

    // ================================================================
    // Getter
    // ================================================================

    /**
     * 获取配置属性
     */
    public TsaProperties getProperties() {
        return properties;
    }
}
