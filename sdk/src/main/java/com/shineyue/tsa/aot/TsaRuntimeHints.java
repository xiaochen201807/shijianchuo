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

        // SM2 签名/密钥相关
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.jcajce.provider.asymmetric.sm2.SM2Signature", all);
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.bouncycastle.jcajce.provider.digest.SM3", all);

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
