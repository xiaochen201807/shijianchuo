package com.shineyue.tsa.aot;

import com.shineyue.tsa.TsaClient;
import com.shineyue.tsa.TsaProperties;
import com.shineyue.tsa.TsaSigner;
import com.shineyue.tsa.exception.TsaException;
import com.shineyue.tsa.model.TimeStampResult;
import com.shineyue.tsa.model.TimeStampVerifyResult;
import com.shineyue.tsa.sm2.Sm2Util;
import com.shineyue.tsa.sm3.Sm3Util;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * GraalVM Native Image 运行时提示 (BouncyCastle + TSA 模型)
 */
public class TsaRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        MemberCategory[] all = MemberCategory.values();

        // 本库核心类型
        hints.reflection().registerType(TsaClient.class, all);
        hints.reflection().registerType(TsaProperties.class, all);
        hints.reflection().registerType(TimeStampResult.class, all);
        hints.reflection().registerType(TimeStampVerifyResult.class, all);
        hints.reflection().registerType(TsaException.class, all);
        hints.reflection().registerType(Sm2Util.class, all);
        hints.reflection().registerType(Sm3Util.class, all);
        hints.reflection().registerType(TsaSigner.class, all);

        // BouncyCastle Provider / 常用类型
        hints.reflection().registerType(BouncyCastleProvider.class, all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey", all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey", all);
        // EC KeyFactory: loadPrivateKey / Sm2Util 通过 KeyFactory.getInstance("EC", "BC") 解析密钥
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.jcajce.provider.asymmetric.ec.KeyFactorySpi$EC", all);
        // EC KeyPairGenerator: Sm2Util.generateKeyPair 通过 KeyPairGenerator.getInstance("EC", "BC") 生成密钥对
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.jcajce.provider.asymmetric.ec.KeyPairGeneratorSpi$EC", all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.jcajce.provider.digest.SM3$Digest", all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.tsp.TimeStampRequest", all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.tsp.TimeStampRequestGenerator", all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.tsp.TimeStampResponse", all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.tsp.TimeStampToken", all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.tsp.TimeStampTokenInfo", all);

        // 验证所需: X.509 证书工厂 + CMS 签名验证
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.jcajce.provider.asymmetric.x509.CertificateFactory", all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.cert.jcajce.JcaX509CertificateConverter", all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.cert.jcajce.JcaSimpleSignerInfoVerifierBuilder", all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.cms.CMSSignedData", all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.cms.SignerInformation", all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.cms.SignerInformationStore", all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.cert.X509CertificateHolder", all);

        // SM2 签名 SPI (BC 1.81: 位于 asymmetric.ec 包, 类名 GMSignatureSpi)
        // TsaSigner 构造器通过 JcaSimpleSignerInfoGeneratorBuilder.build("SM3withSM2", ...) 触发
        // Signature.getInstance("SM3withSM2", "BC") -> GMSignatureSpi$sm3WithSM2
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.jcajce.provider.asymmetric.ec.GMSignatureSpi", all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.jcajce.provider.asymmetric.ec.GMSignatureSpi$sm3WithSM2", all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.jcajce.provider.asymmetric.ec.GMSignatureSpi$sha256WithSM2", all);
        // SM3 摘要算法主类 (MessageDigest.getInstance("SM3", "BC") 依赖)
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.jcajce.provider.digest.SM3", all);
        // SM3 摘要实现链: GeneralDigest(基类, 含 xBuf/xBufOff 状态) -> SM3Digest
        // BCMessageDigest(JCA 包装, engineUpdate 委托到 lightweight Digest)
        // 注册为反射类型可禁用 GraalVM 逃逸分析, 确保实例字段初始化器 (xBuf=new byte[4]) 正确执行,
        // 避免 Native Image 中 xBufOff 状态损坏导致 ArrayIndexOutOfBoundsException
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.crypto.digests.GeneralDigest", all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.crypto.digests.SM3Digest", all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.jcajce.provider.digest.BCMessageDigest", all);

        // 摘要算法 (验证时需要)
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.jcajce.provider.digest.SHA256$Digest", all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.jcajce.provider.digest.SHA384$Digest", all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.jcajce.provider.digest.SHA512$Digest", all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.jcajce.provider.digest.SHA1$Digest", all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.jcajce.provider.digest.MD5$Digest", all);

        // 签名所需: CMS 签名器生成 + TSP 令牌生成
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder", all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.cms.DefaultSignedAttributeTableGenerator", all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.tsp.TimeStampTokenGenerator", all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.cert.jcajce.JcaCertStore", all);

        // BC 资源 (算法配置等)
        hints.resources().registerPattern("org/bouncycastle/.*");
        hints.resources().registerPattern("META-INF/services/.*");
    }
}
