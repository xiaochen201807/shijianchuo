package com.shineyue.tsa.aot;

import com.shineyue.tsa.TsaClient;
import com.shineyue.tsa.TsaProperties;
import com.shineyue.tsa.exception.TsaException;
import com.shineyue.tsa.model.TimeStampResult;
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
        hints.reflection().registerType(TsaException.class, all);
        hints.reflection().registerType(Sm2Util.class, all);
        hints.reflection().registerType(Sm3Util.class, all);

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

        // BC 资源 (算法配置等)
        hints.resources().registerPattern("org/bouncycastle/.*");
        hints.resources().registerPattern("META-INF/services/.*");
    }
}
