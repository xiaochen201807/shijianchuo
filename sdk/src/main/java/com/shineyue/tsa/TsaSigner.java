package com.shineyue.tsa;

import com.shineyue.tsa.exception.TsaException;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.cmp.PKIStatus;
import org.bouncycastle.asn1.cmp.PKIStatusInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.tsp.*;
import org.bouncycastle.util.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;

/**
 * TSA 本地签名器 - 基于 BouncyCastle 的 RFC 3161 时间戳签名
 *
 * 直接在 Java 进程内完成 SM3withSM2 时间戳签名，无需外部 TSA 服务器。
 * 输出与 Tongsuo/OpenSSL 签发的时间戳完全兼容。
 *
 * 使用示例:
 *   X509Certificate cert = TsaSigner.loadCertificate(new FileInputStream("tsacert.pem"));
 *   PrivateKey key = TsaSigner.loadPrivateKey(new FileInputStream("tsakey.pem"));
 *   TsaSigner signer = new TsaSigner(cert, key);
 *
 *   // 接收 TimeStampReq (DER)，返回 TimeStampResp (DER)
 *   byte[] responseDer = signer.sign(requestDer);
 */
public class TsaSigner {

    private static final Logger logger = LoggerFactory.getLogger(TsaSigner.class);

    /** SM3 摘要算法 OID */
    public static final ASN1ObjectIdentifier SM3_OID = new ASN1ObjectIdentifier("1.2.156.10197.1.401");

    /** SM3withSM2 签名算法名 */
    private static final String SIGNATURE_ALGORITHM = "SM3withSM2";

    /** 默认 TSA 策略 OID */
    private static final String DEFAULT_POLICY_OID = "1.2.3.4.1";

    /** 序列号生成器 */
    private static final AtomicLong SERIAL_COUNTER = new AtomicLong(System.currentTimeMillis());

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final X509Certificate certificate;
    private final PrivateKey privateKey;
    private final String policyOid;
    private final TimeStampTokenGenerator tokenGenerator;

    /**
     * 创建 TSA 本地签名器
     *
     * @param certificate TSA 签名证书 (SM2)
     * @param privateKey  TSA 签名私钥 (SM2)
     */
    public TsaSigner(X509Certificate certificate, PrivateKey privateKey) {
        this(certificate, privateKey, DEFAULT_POLICY_OID, Collections.emptyList());
    }

    /**
     * 创建 TSA 本地签名器
     *
     * @param certificate TSA 签名证书 (SM2)
     * @param privateKey  TSA 签名私钥 (SM2)
     * @param policyOid   TSA 策略 OID
     */
    public TsaSigner(X509Certificate certificate, PrivateKey privateKey, String policyOid) {
        this(certificate, privateKey, policyOid, Collections.emptyList());
    }

    /**
     * 创建 TSA 本地签名器（含证书链）
     *
     * @param certificate TSA 签名证书 (SM2)
     * @param privateKey  TSA 签名私钥 (SM2)
     * @param policyOid   TSA 策略 OID
     * @param certChain   证书链 (CA 证书等)，嵌入到时间戳响应的 CMS SignedData 中
     */
    public TsaSigner(X509Certificate certificate, PrivateKey privateKey, String policyOid,
                     List<X509Certificate> certChain) {
        this.certificate = certificate;
        this.privateKey = privateKey;
        this.policyOid = policyOid;

        try {
            // 构建 SM3withSM2 签名器信息生成器
            org.bouncycastle.cms.SignerInfoGenerator signerInfoGen =
                    new org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder()
                            .setProvider("BC")
                            .setSignedAttributeGenerator(new DefaultSignedAttributeTableGenerator())
                            .build(SIGNATURE_ALGORITHM, privateKey, certificate);

            // SM3 摘要计算器
            DigestCalculator digestCalculator = new SM3DigestCalculator();

            // 构建 Token 生成器 (BC 1.81: 3 参数构造函数)
            this.tokenGenerator = new TimeStampTokenGenerator(
                    signerInfoGen,
                    digestCalculator,
                    new ASN1ObjectIdentifier(policyOid)
            );

            // 添加证书链: TSA 签名证书 + CA 证书
            List<X509CertificateHolder> certHolders = new ArrayList<>();
            certHolders.add(new JcaX509CertificateHolder(certificate));
            if (certChain != null) {
                for (X509Certificate chainCert : certChain) {
                    certHolders.add(new JcaX509CertificateHolder(chainCert));
                }
            }
            this.tokenGenerator.addCertificates(
                    new org.bouncycastle.util.CollectionStore<>(certHolders));

            // RFC 3161: Accuracy.millis 取值范围为 (1..999)，是秒以下的余数。
            // 1000ms 即 1 秒，应通过 seconds 字段表达。
            this.tokenGenerator.setAccuracySeconds(1);

            logger.info("TsaSigner initialized: cert={}, policy={}, algorithm={}, chainSize={}",
                    certificate.getSubjectX500Principal().getName(),
                    policyOid, SIGNATURE_ALGORITHM, certHolders.size());

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize TsaSigner", e);
        }
    }

    /**
     * 对 RFC 3161 TimeStampReq 进行签名
     *
     * @param requestDer TimeStampReq DER 编码
     * @return TimeStampResp DER 编码
     * @throws TsaException 如果签名失败
     */
    public byte[] sign(byte[] requestDer) throws TsaException {
        if (requestDer == null || requestDer.length == 0) {
            throw new TsaException("TSA_SIGN_EMPTY", "TimeStampReq cannot be null or empty");
        }

        try {
            // 1. 解析请求
            TimeStampRequest request = new TimeStampRequest(requestDer);

            // 2. 生成序列号
            BigInteger serialNumber = BigInteger.valueOf(SERIAL_COUNTER.getAndIncrement());

            // 3. 生成时间戳令牌 (BC 1.81: generate 方法)
            TimeStampToken token = tokenGenerator.generate(request, serialNumber, new Date());

            // 4. 构建 TimeStampResp DER (SEQUENCE { status, token })
            PKIStatusInfo statusInfo = new PKIStatusInfo(PKIStatus.granted, null, null);
            ContentInfo contentInfo = ContentInfo.getInstance(token.getEncoded());
            byte[] responseDer = new DERSequence(new org.bouncycastle.asn1.ASN1Encodable[]{
                    statusInfo, contentInfo
            }).getEncoded();

            logger.debug("Timestamp signed: serial={}, size={} bytes",
                    serialNumber.toString(16), responseDer.length);

            return responseDer;

        } catch (TSPException e) {
            logger.error("Timestamp signing failed", e);
            throw new TsaException("TSA_SIGN_FAILED", "Timestamp signing failed: " + e.getMessage(), e);
        } catch (IOException e) {
            logger.error("Timestamp encoding failed", e);
            throw new TsaException("TSA_SIGN_ENCODE", "Failed to encode timestamp response", e);
        }
    }

    /**
     * 获取签名证书
     */
    public X509Certificate getCertificate() {
        return certificate;
    }

    // ================================================================
    // 静态工具方法
    // ================================================================

    /**
     * 从 PEM 输入流加载 X.509 证书
     */
    public static X509Certificate loadCertificate(InputStream certStream) throws TsaException {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509", "BC");
            return (X509Certificate) factory.generateCertificate(certStream);
        } catch (Exception e) {
            throw new TsaException("TSA_CERT_LOAD", "Failed to load certificate", e);
        }
    }

    /**
     * 从 PEM 输入流加载私钥
     */
    public static PrivateKey loadPrivateKey(InputStream keyStream) throws TsaException {
        try {
            String pem = new String(keyStream.readAllBytes());
            // 去除 PEM 头尾和空白
            pem = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                     .replace("-----END PRIVATE KEY-----", "")
                     .replace("-----BEGIN EC PRIVATE KEY-----", "")
                     .replace("-----END EC PRIVATE KEY-----", "")
                     .replaceAll("\\s", "");

            byte[] der = Base64.getDecoder().decode(pem);
            java.security.spec.PKCS8EncodedKeySpec keySpec = new java.security.spec.PKCS8EncodedKeySpec(der);
            KeyFactory kf = KeyFactory.getInstance("EC", "BC");
            return kf.generatePrivate(keySpec);
        } catch (Exception e) {
            throw new TsaException("TSA_KEY_LOAD", "Failed to load private key", e);
        }
    }

    // ================================================================
    // SM3 摘要计算器实现
    // ================================================================

    /**
     * SM3 DigestCalculator 实现 (供 BouncyCastle TSP 使用)
     */
    private static class SM3DigestCalculator implements DigestCalculator {

        private final org.bouncycastle.crypto.digests.SM3Digest digest = new org.bouncycastle.crypto.digests.SM3Digest();

        @Override
        public org.bouncycastle.asn1.x509.AlgorithmIdentifier getAlgorithmIdentifier() {
            return new org.bouncycastle.asn1.x509.AlgorithmIdentifier(SM3_OID);
        }

        @Override
        public OutputStream getOutputStream() {
            return new DigestOutputStream(digest);
        }

        @Override
        public byte[] getDigest() {
            byte[] result = new byte[digest.getDigestSize()];
            digest.doFinal(result, 0);
            return result;
        }
    }

    /**
     * 将 BC Digest 包装为 OutputStream
     */
    private static class DigestOutputStream extends OutputStream {
        private final org.bouncycastle.crypto.Digest digest;

        DigestOutputStream(org.bouncycastle.crypto.Digest digest) {
            this.digest = digest;
        }

        @Override
        public void write(int b) {
            digest.update((byte) b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            digest.update(b, off, len);
        }
    }
}
