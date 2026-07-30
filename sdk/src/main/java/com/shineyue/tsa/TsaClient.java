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
import org.bouncycastle.tsp.TSPAlgorithms;
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
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;

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
     * @return 时间戳结果
     * @throws TsaException 如果请求失败
     */
    public TimeStampResult timestamp(byte[] data) throws TsaException {
        if (data == null) {
            throw new TsaException("TSA_DATA_NULL", "Input data cannot be null");
        }

        logger.debug("Timestamping {} bytes of data", data.length);

        // 1. 计算 SM3 摘要
        byte[] hash = Sm3Util.hash(data);
        logger.debug("SM3 hash: {}", Sm3Util.toHex(hash));

        // 2. 发送时间戳请求
        return timestampWithHash(hash, SM3_OID);
    }

    /**
     * 对字符串请求时间戳
     *
     * @param text 输入字符串
     * @return 时间戳结果
     * @throws TsaException 如果请求失败
     */
    public TimeStampResult timestamp(String text) throws TsaException {
        return timestamp(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 对输入流数据请求时间戳 (流式处理，适合大文件)
     * 注意: 此方法会先计算整个流的 SM3 摘要
     *
     * @param inputStream 输入流
     * @return 时间戳结果
     * @throws TsaException 如果请求失败
     */
    public TimeStampResult timestamp(InputStream inputStream) throws TsaException {
        try {
            byte[] hash = Sm3Util.hash(inputStream);
            return timestampWithHash(hash, SM3_OID);
        } catch (IOException e) {
            throw new TsaException("TSA_STREAM_ERROR", "Failed to read input stream", e);
        }
    }

    /**
     * 使用预先计算的摘要请求时间戳
     * 适用于客户端已自行计算摘要的场景
     *
     * @param hash         预计算的摘要值
     * @param hashOid      摘要算法 OID (SM3: 1.2.156.10197.1.401)
     * @return 时间戳结果
     * @throws TsaException 如果请求失败
     */
    public TimeStampResult timestampWithHash(byte[] hash, ASN1ObjectIdentifier hashOid) throws TsaException {
        if (hash == null || hash.length == 0) {
            throw new TsaException("TSA_HASH_EMPTY", "Hash value cannot be null or empty");
        }

        logger.info("Sending timestamp request to TSA: url={}, hashAlgorithm={}",
                properties.getUrl(), hashOid);

        // 1. 构造 RFC 3161 TimeStampReq
        byte[] requestDer = buildTimeStampRequest(hash, hashOid);
        logger.debug("TimeStampReq size: {} bytes", requestDer.length);

        // 2. 发送 HTTP POST 请求
        byte[] responseDer = sendHttpPost(requestDer);
        logger.debug("TimeStampResp size: {} bytes", responseDer.length);

        // 3. 解析响应
        return parseTimeStampResponse(responseDer, hash, hashOid, requestDer);
    }

    /**
     * 使用 SM3 预计算摘要请求时间戳
     *
     * @param sm3Hash SM3 摘要值 (32 字节)
     * @return 时间戳结果
     * @throws TsaException 如果请求失败
     */
    public TimeStampResult timestampWithSm3Hash(byte[] sm3Hash) throws TsaException {
        return timestampWithHash(sm3Hash, SM3_OID);
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
     * @return 验证结果，包含签名有效性、摘要匹配、证书信息等
     * @throws TsaException 如果验证过程出错
     */
    public TimeStampVerifyResult verifyTimestamp(byte[] data, byte[] responseDer) throws TsaException {
        if (data == null) {
            throw new TsaException("TSA_DATA_NULL", "Input data cannot be null");
        }
        if (responseDer == null || responseDer.length == 0) {
            throw new TsaException("TSA_RESPONSE_NULL", "Timestamp response data cannot be null or empty");
        }

        try {
            // 1. 解析时间戳响应
            TimeStampResponse tsResponse = new TimeStampResponse(responseDer);

            // 2. 检查响应状态
            int status = tsResponse.getStatus();
            if (status != 0 && status != 1) {
                throw new TsaException("TSA_REJECTED",
                        "TSA response status: " + status + " - " + tsResponse.getStatusString());
            }

            TimeStampToken token = tsResponse.getTimeStampToken();
            if (token == null) {
                throw new TsaException("TSA_NO_TOKEN", "No timestamp token in response");
            }

            // 3. 从 Token 内部提取签名证书
            CMSSignedData cmsData = new CMSSignedData(token.getEncoded());
            SignerInformation signerInfo = cmsData.getSignerInfos().getSigners().iterator().next();
            JcaSimpleSignerInfoVerifierBuilder certConverter = new JcaSimpleSignerInfoVerifierBuilder();
            certConverter.setProvider("BC");

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
                throw new TsaException("TSA_NO_SIGNER_CERT", "No signer certificate found in token");
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
            byte[] expectedHash = Sm3Util.hash(data);
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

        } catch (TsaException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Timestamp verification failed", e);
            throw new TsaException("TSA_VERIFY_FAILED", "Timestamp verification failed", e);
        }
    }

    /**
     * 验证时间戳令牌（字符串数据 + Base64 编码的响应）
     *
     * @param text 原始文本
     * @param responseBase64 Base64 编码的时间戳响应
     * @return 验证结果
     * @throws TsaException 如果验证过程出错
     */
    public TimeStampVerifyResult verifyTimestamp(String text, String responseBase64) throws TsaException {
        if (text == null) {
            throw new TsaException("TSA_DATA_NULL", "Input text cannot be null");
        }
        if (responseBase64 == null || responseBase64.isEmpty()) {
            throw new TsaException("TSA_RESPONSE_NULL", "Response Base64 cannot be null or empty");
        }

        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        byte[] responseDer = Base64.getDecoder().decode(responseBase64);
        return verifyTimestamp(data, responseDer);
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
     */
    private byte[] sendHttpPost(byte[] requestBody) throws TsaException {
        HttpURLConnection connection = null;
        try {
            URL url = URI.create(properties.getUrl()).toURL();
            connection = (HttpURLConnection) url.openConnection();

            // 设置请求方法
            connection.setRequestMethod("POST");

            // 设置 Content-Type (RFC 3161)
            connection.setRequestProperty("Content-Type", CONTENT_TYPE_QUERY);
            connection.setRequestProperty("Accept", CONTENT_TYPE_REPLY);

            // 设置超时
            connection.setConnectTimeout(properties.getConnectTimeout());
            connection.setReadTimeout(properties.getReadTimeout());

            // 启用输出
            connection.setDoOutput(true);
            connection.setDoInput(true);

            // 发送请求体
            try (OutputStream os = connection.getOutputStream()) {
                os.write(requestBody);
                os.flush();
            }

            // 获取响应码
            int responseCode = connection.getResponseCode();
            logger.debug("HTTP response code: {}", responseCode);

            if (responseCode != 200) {
                byte[] errorBytes = readStream(connection.getErrorStream());
                String errorBody = errorBytes != null ? new String(errorBytes, StandardCharsets.UTF_8) : "";
                throw new TsaException("TSA_HTTP_ERROR",
                        "TSA server returned HTTP " + responseCode + ": " + errorBody);
            }

            // 检查 Content-Type
            String contentType = connection.getContentType();
            if (contentType != null && !contentType.contains(CONTENT_TYPE_REPLY)) {
                logger.warn("Unexpected Content-Type: {}", contentType);
            }

            // 读取响应体
            byte[] responseBody = readStream(connection.getInputStream());
            if (responseBody == null || responseBody.length == 0) {
                throw new TsaException("TSA_EMPTY_RESPONSE", "TSA server returned empty response");
            }

            return responseBody;

        } catch (IOException e) {
            throw new TsaException("TSA_HTTP_IO", "HTTP request to TSA failed", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
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
