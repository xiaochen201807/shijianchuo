package com.shineyue.tsa.sm2;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.CryptoException;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.SM2Engine;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.*;
import org.bouncycastle.crypto.signers.SM2Signer;
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey;
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.jce.spec.ECPrivateKeySpec;
import org.bouncycastle.jce.spec.ECPublicKeySpec;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.util.BigIntegers;
import org.bouncycastle.util.encoders.Hex;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.InvalidKeySpecException;

/**
 * SM2 国密非对称加密算法工具类
 *
 * SM2 是基于椭圆曲线密码 (ECC) 的公钥密码算法标准 (GM/T 0003-2012)
 * 使用曲线 sm2p256v1，密钥长度 256 位
 *
 * 主要功能:
 *   1. 生成 SM2 密钥对
 *   2. SM2 数字签名与验证 (使用 SM3 作为摘要)
 *   3. SM2 加密与解密
 *   4. 密钥序列化/反序列化
 *
 * 使用示例:
 *   // 1. 生成密钥对
 *   KeyPair keyPair = Sm2Util.generateKeyPair();
 *
 *   // 2. 签名
 *   byte[] signature = Sm2Util.sign("Hello, TSA!".getBytes(), keyPair.getPrivate());
 *
 *   // 3. 验证签名
 *   boolean valid = Sm2Util.verify("Hello, TSA!".getBytes(), signature, keyPair.getPublic());
 *
 *   // 4. 加密
 *   byte[] ciphertext = Sm2Util.encrypt("Secret".getBytes(), keyPair.getPublic());
 *
 *   // 5. 解密
 *   byte[] plaintext = Sm2Util.decrypt(ciphertext, keyPair.getPrivate());
 */
public final class Sm2Util {

    /**
     * SM2 曲线名称
     */
    public static final String CURVE_NAME = "sm2p256v1";

    /**
     * SM2 算法名称
     */
    public static final String ALGORITHM = "SM2";

    /**
     * SM2 签名算法名称 (SM3 摘要 + SM2 签名)
     */
    public static final String SIGNATURE_ALGORITHM = "SM3withSM2";

    /**
     * SM2 OID (1.2.156.10197.1.301)
     */
    public static final String OID = "1.2.156.10197.1.301";

    /**
     * 默认用户 ID (国密标准默认值)
     */
    public static final byte[] DEFAULT_USER_ID = "1234567812345678".getBytes(StandardCharsets.UTF_8);

    static {
        // 确保 BouncyCastle Provider 已注册
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private Sm2Util() {
        // 工具类，禁止实例化
    }

    // ================================================================
    // 1. 密钥对生成
    // ================================================================

    /**
     * 生成 SM2 密钥对
     *
     * @return KeyPair 对象 (包含 BCECPrivateKey 和 BCECPublicKey)
     */
    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "BC");
            generator.initialize(new ECGenParameterSpec(CURVE_NAME));
            return generator.generateKeyPair();
        } catch (NoSuchProviderException | NoSuchAlgorithmException |
                 java.security.InvalidAlgorithmParameterException e) {
            throw new RuntimeException("Failed to generate SM2 key pair", e);
        }
    }

    /**
     * 生成 SM2 密钥对 (使用底层 BC API)
     *
     * @return AsymmetricCipherKeyPair 对象
     */
    public static AsymmetricCipherKeyPair generateKeyPairBc() {
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec(CURVE_NAME);
        ECKeyGenerationParameters keyGenParams = new ECKeyGenerationParameters(
                new ECDomainParameters(spec.getCurve(), spec.getG(), spec.getN(), spec.getH()),
                new SecureRandom()
        );
        ECKeyPairGenerator generator = new ECKeyPairGenerator();
        generator.init(keyGenParams);
        return generator.generateKeyPair();
    }

    // ================================================================
    // 2. 数字签名 (SM2 + SM3) - 使用 BC 底层 API
    // ================================================================

    /**
     * SM2 数字签名
     * 使用 SM3 作为摘要算法，默认用户 ID
     *
     * @param data       原始数据
     * @param privateKey SM2 私钥
     * @return 签名值 (DER 编码)
     * @throws CryptoException 如果签名失败
     */
    public static byte[] sign(byte[] data, PrivateKey privateKey) throws CryptoException {
        return sign(data, privateKey, DEFAULT_USER_ID);
    }

    /**
     * SM2 数字签名 (指定用户 ID)
     * 使用 BouncyCastle 底层 SM2Signer API
     *
     * @param data       原始数据
     * @param privateKey SM2 私钥
     * @param userId     用户 ID (国密标准中称为 "Z值" 的输入)
     * @return 签名值 (DER 编码)
     * @throws CryptoException 如果签名失败
     */
    public static byte[] sign(byte[] data, PrivateKey privateKey, byte[] userId) throws CryptoException {
        BCECPrivateKey bcPrivKey = (BCECPrivateKey) privateKey;
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec(CURVE_NAME);

        ECPrivateKeyParameters privKeyParams = new ECPrivateKeyParameters(
                bcPrivKey.getD(),
                new ECDomainParameters(spec.getCurve(), spec.getG(), spec.getN(), spec.getH())
        );

        SM2Signer signer = new SM2Signer();
        ParametersWithID paramWithId = new ParametersWithID(privKeyParams, userId);
        signer.init(true, paramWithId);
        signer.update(data, 0, data.length);
        return signer.generateSignature();
    }

    /**
     * SM2 签名验证
     * 使用 SM3 作为摘要算法，默认用户 ID
     *
     * @param data      原始数据
     * @param signature 签名值 (DER 编码)
     * @param publicKey SM2 公钥
     * @return true 如果签名有效，false 如果无效
     */
    public static boolean verify(byte[] data, byte[] signature, PublicKey publicKey) {
        return verify(data, signature, publicKey, DEFAULT_USER_ID);
    }

    /**
     * SM2 签名验证 (指定用户 ID)
     *
     * @param data      原始数据
     * @param signature 签名值 (DER 编码)
     * @param publicKey SM2 公钥
     * @param userId    用户 ID
     * @return true 如果签名有效，false 如果无效
     */
    public static boolean verify(byte[] data, byte[] signature, PublicKey publicKey, byte[] userId) {
        BCECPublicKey bcPubKey = (BCECPublicKey) publicKey;
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec(CURVE_NAME);

        ECPublicKeyParameters pubKeyParams = new ECPublicKeyParameters(
                bcPubKey.getQ(),
                new ECDomainParameters(spec.getCurve(), spec.getG(), spec.getN(), spec.getH())
        );

        SM2Signer signer = new SM2Signer();
        ParametersWithID paramWithId = new ParametersWithID(pubKeyParams, userId);
        signer.init(false, paramWithId);
        signer.update(data, 0, data.length);
        return signer.verifySignature(signature);
    }

    /**
     * 使用底层 BC API 进行 SM2 签名
     *
     * @param data        原始数据
     * @param privateKey  SM2 私钥参数 (BC API)
     * @param userId      用户 ID
     * @return 签名值 (DER 编码)
     * @throws CryptoException 如果签名失败
     */
    public static byte[] signWithParams(byte[] data, ECPrivateKeyParameters privateKey, byte[] userId)
            throws CryptoException {
        SM2Signer signer = new SM2Signer();
        ParametersWithID paramWithId = new ParametersWithID(privateKey, userId);
        signer.init(true, paramWithId);
        signer.update(data, 0, data.length);
        return signer.generateSignature();
    }

    /**
     * 使用底层 BC API 进行 SM2 签名验证
     *
     * @param data      原始数据
     * @param signature 签名值
     * @param publicKey SM2 公钥参数 (BC API)
     * @param userId    用户 ID
     * @return true 如果签名有效
     */
    public static boolean verifyWithParams(byte[] data, byte[] signature,
                                           ECPublicKeyParameters publicKey, byte[] userId) {
        SM2Signer signer = new SM2Signer();
        ParametersWithID paramWithId = new ParametersWithID(publicKey, userId);
        signer.init(false, paramWithId);
        signer.update(data, 0, data.length);
        return signer.verifySignature(signature);
    }

    // ================================================================
    // 3. 加密/解密 (SM2 公钥加密)
    // ================================================================

    /**
     * SM2 公钥加密
     * 适合加密短数据 (如对称密钥)，不适合加密长数据
     *
     * @param plaintext 明文数据
     * @param publicKey SM2 公钥
     * @return 密文数据 (C1C3C2 格式)
     */
    public static byte[] encrypt(byte[] plaintext, PublicKey publicKey) {
        BCECPublicKey bcPubKey = (BCECPublicKey) publicKey;
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec(CURVE_NAME);

        ECPublicKeyParameters pubKeyParams = new ECPublicKeyParameters(
                bcPubKey.getQ(),
                new ECDomainParameters(spec.getCurve(), spec.getG(), spec.getN(), spec.getH())
        );
        return encryptBc(plaintext, pubKeyParams);
    }

    /**
     * SM2 公钥加密 (使用 BC 底层 API)
     *
     * @param plaintext   明文数据
     * @param publicKey   SM2 公钥参数
     * @return 密文数据
     */
    public static byte[] encryptBc(byte[] plaintext, ECPublicKeyParameters publicKey) {
        SM2Engine engine = new SM2Engine(SM2Engine.Mode.C1C3C2);
        engine.init(true, new ParametersWithRandom(publicKey, new SecureRandom()));
        try {
            return engine.processBlock(plaintext, 0, plaintext.length);
        } catch (InvalidCipherTextException e) {
            throw new RuntimeException("SM2 encryption failed", e);
        }
    }

    /**
     * SM2 私钥解密
     *
     * @param ciphertext 密文数据
     * @param privateKey  SM2 私钥
     * @return 明文数据
     */
    public static byte[] decrypt(byte[] ciphertext, PrivateKey privateKey) {
        BCECPrivateKey bcPrivKey = (BCECPrivateKey) privateKey;
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec(CURVE_NAME);

        ECPrivateKeyParameters privKeyParams = new ECPrivateKeyParameters(
                bcPrivKey.getD(),
                new ECDomainParameters(spec.getCurve(), spec.getG(), spec.getN(), spec.getH())
        );
        return decryptBc(ciphertext, privKeyParams);
    }

    /**
     * SM2 私钥解密 (使用 BC 底层 API)
     *
     * @param ciphertext 密文数据
     * @param privateKey SM2 私钥参数
     * @return 明文数据
     */
    public static byte[] decryptBc(byte[] ciphertext, ECPrivateKeyParameters privateKey) {
        SM2Engine engine = new SM2Engine(SM2Engine.Mode.C1C3C2);
        engine.init(false, privateKey);
        try {
            return engine.processBlock(ciphertext, 0, ciphertext.length);
        } catch (InvalidCipherTextException e) {
            throw new RuntimeException("SM2 decryption failed", e);
        }
    }

    // ================================================================
    // 4. 密钥序列化/反序列化
    // ================================================================

    /**
     * 将私钥编码为十六进制字符串
     *
     * @param privateKey SM2 私钥
     * @return 私钥的十六进制字符串 (64 字符)
     */
    public static String privateKeyToHex(PrivateKey privateKey) {
        BCECPrivateKey bcKey = (BCECPrivateKey) privateKey;
        BigInteger d = bcKey.getD();
        return Hex.toHexString(BigIntegers.asUnsignedByteArray(32, d));
    }

    /**
     * 将公钥编码为十六进制字符串 (未压缩格式)
     *
     * @param publicKey SM2 公钥
     * @return 公钥的十六进制字符串 (130 字符，以 04 开头)
     */
    public static String publicKeyToHex(PublicKey publicKey) {
        BCECPublicKey bcKey = (BCECPublicKey) publicKey;
        ECPoint q = bcKey.getQ();
        return Hex.toHexString(q.getEncoded(false));
    }

    /**
     * 将公钥编码为十六进制字符串 (压缩格式)
     *
     * @param publicKey SM2 公钥
     * @return 公钥的十六进制字符串 (66 字符)
     */
    public static String publicKeyToHexCompressed(PublicKey publicKey) {
        BCECPublicKey bcKey = (BCECPublicKey) publicKey;
        ECPoint q = bcKey.getQ();
        return Hex.toHexString(q.getEncoded(true));
    }

    /**
     * 从十六进制字符串恢复私钥
     *
     * @param hex 私钥的十六进制字符串
     * @return SM2 私钥
     */
    public static PrivateKey privateKeyFromHex(String hex) {
        try {
            BigInteger d = new BigInteger(1, Hex.decode(hex));
            ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec(CURVE_NAME);
            ECPrivateKeySpec privateKeySpec = new ECPrivateKeySpec(d, spec);
            KeyFactory keyFactory = KeyFactory.getInstance("EC", "BC");
            return keyFactory.generatePrivate(privateKeySpec);
        } catch (NoSuchProviderException | NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to restore SM2 private key", e);
        } catch (InvalidKeySpecException e) {
            throw new RuntimeException("Invalid private key hex", e);
        }
    }

    /**
     * 从十六进制字符串恢复公钥
     *
     * @param hex 公钥的十六进制字符串
     * @return SM2 公钥
     */
    public static PublicKey publicKeyFromHex(String hex) {
        try {
            ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec(CURVE_NAME);
            ECPoint point = spec.getCurve().decodePoint(Hex.decode(hex));
            ECPublicKeySpec pubKeySpec = new ECPublicKeySpec(point, spec);
            KeyFactory keyFactory = KeyFactory.getInstance("EC", "BC");
            return keyFactory.generatePublic(pubKeySpec);
        } catch (NoSuchProviderException | NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to restore SM2 public key", e);
        } catch (InvalidKeySpecException e) {
            throw new RuntimeException("Invalid public key hex", e);
        }
    }

    // ================================================================
    // 5. 辅助方法
    // ================================================================

    /**
     * 获取 SM2 曲线参数
     *
     * @return ECNamedCurveParameterSpec
     */
    public static ECNamedCurveParameterSpec getCurveSpec() {
        return ECNamedCurveTable.getParameterSpec(CURVE_NAME);
    }

    /**
     * 将 JCA KeyPair 转换为 BC 密钥参数
     *
     * @param keyPair JCA 密钥对
     * @return BC 密钥参数数组 [privateKeyParams, publicKeyParams]
     */
    public static CipherParameters[] toBcKeyParams(KeyPair keyPair) {
        BCECPrivateKey privKey = (BCECPrivateKey) keyPair.getPrivate();
        BCECPublicKey pubKey = (BCECPublicKey) keyPair.getPublic();

        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec(CURVE_NAME);
        ECDomainParameters domain = new ECDomainParameters(
                spec.getCurve(), spec.getG(), spec.getN(), spec.getH()
        );

        ECPrivateKeyParameters privParams = new ECPrivateKeyParameters(privKey.getD(), domain);
        ECPublicKeyParameters pubParams = new ECPublicKeyParameters(pubKey.getQ(), domain);

        return new CipherParameters[]{privParams, pubParams};
    }
}
