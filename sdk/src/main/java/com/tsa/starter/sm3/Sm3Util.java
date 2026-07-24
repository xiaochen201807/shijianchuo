package com.tsa.starter.sm3;

import org.bouncycastle.crypto.digests.SM3Digest;
import org.bouncycastle.util.encoders.Hex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Security;

/**
 * SM3 国密摘要算法工具类
 *
 * SM3 是中国国家密码管理局发布的密码杂凑算法标准 (GM/T 0004-2012)
 * 输出 256 位 (32 字节) 摘要值
 *
 * 主要功能:
 *   1. 计算数据的 SM3 摘要
 *   2. 支持字节数组、字符串、流式数据
 *   3. 返回字节数组或十六进制字符串
 *
 * 使用示例:
 *   // 计算字符串的 SM3 摘要 (十六进制输出)
 *   String hash = Sm3Util.hashHex("Hello, TSA!");
 *
 *   // 计算字节数组的 SM3 摘要
 *   byte[] data = "test".getBytes(StandardCharsets.UTF_8);
 *   byte[] hashBytes = Sm3Util.hash(data);
 *
 *   // 计算 InputStream 的 SM3 摘要 (适合大文件)
 *   String fileHash = Sm3Util.hashHex(inputStream);
 */
public final class Sm3Util {

    /**
     * SM3 摘要长度 (字节)
     */
    public static final int DIGEST_SIZE = 32;

    /**
     * SM3 摘要长度 (位)
     */
    public static final int DIGEST_BITS = 256;

    /**
     * SM3 算法名称
     */
    public static final String ALGORITHM = "SM3";

    /**
     * SM3 OID (1.2.156.10197.1.401)
     */
    public static final String OID = "1.2.156.10197.1.401";

    static {
        // 确保 BouncyCastle Provider 已注册
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        }
    }

    private Sm3Util() {
        // 工具类，禁止实例化
    }

    // ================================================================
    // 核心方法: 计算摘要
    // ================================================================

    /**
     * 计算字节数组的 SM3 摘要
     *
     * @param data 原始数据
     * @return 32 字节的 SM3 摘要值
     * @throws IllegalArgumentException 如果 data 为 null
     */
    public static byte[] hash(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("Input data cannot be null");
        }
        return hashInternal(data, 0, data.length);
    }

    /**
     * 计算字节数组指定范围的 SM3 摘要
     *
     * @param data   原始数据
     * @param offset 起始偏移
     * @param length 数据长度
     * @return 32 字节的 SM3 摘要值
     */
    public static byte[] hash(byte[] data, int offset, int length) {
        if (data == null) {
            throw new IllegalArgumentException("Input data cannot be null");
        }
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException("Invalid offset or length");
        }
        return hashInternal(data, offset, length);
    }

    /**
     * 计算字符串的 SM3 摘要 (UTF-8 编码)
     *
     * @param text 输入字符串
     * @return 32 字节的 SM3 摘要值
     */
    public static byte[] hash(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Input text cannot be null");
        }
        return hash(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 计算输入流的 SM3 摘要 (流式处理，适合大文件)
     * 注意: 此方法不会关闭输入流
     *
     * @param inputStream 输入流
     * @return 32 字节的 SM3 摘要值
     * @throws java.io.IOException 如果读取流时发生错误
     */
    public static byte[] hash(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            throw new IllegalArgumentException("InputStream cannot be null");
        }
        SM3Digest digest = new SM3Digest();
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            digest.update(buffer, 0, bytesRead);
        }
        byte[] result = new byte[digest.getDigestSize()];
        digest.doFinal(result, 0);
        return result;
    }

    // ================================================================
    // 十六进制输出方法
    // ================================================================

    /**
     * 计算字节数组的 SM3 摘要并返回十六进制字符串
     *
     * @param data 原始数据
     * @return 64 字符的十六进制摘要字符串 (小写)
     */
    public static String hashHex(byte[] data) {
        return Hex.toHexString(hash(data));
    }

    /**
     * 计算字符串的 SM3 摘要并返回十六进制字符串 (UTF-8 编码)
     *
     * @param text 输入字符串
     * @return 64 字符的十六进制摘要字符串 (小写)
     */
    public static String hashHex(String text) {
        return Hex.toHexString(hash(text));
    }

    /**
     * 计算输入流的 SM3 摘要并返回十六进制字符串
     *
     * @param inputStream 输入流
     * @return 64 字符的十六进制摘要字符串 (小写)
     * @throws java.io.IOException 如果读取流时发生错误
     */
    public static String hashHex(InputStream inputStream) throws IOException {
        return Hex.toHexString(hash(inputStream));
    }

    // ================================================================
    // Base64 输出方法
    // ================================================================

    /**
     * 计算字节数组的 SM3 摘要并返回 Base64 字符串
     *
     * @param data 原始数据
     * @return Base64 编码的摘要字符串
     */
    public static String hashBase64(byte[] data) {
        return java.util.Base64.getEncoder().encodeToString(hash(data));
    }

    /**
     * 计算字符串的 SM3 摘要并返回 Base64 字符串
     *
     * @param text 输入字符串
     * @return Base64 编码的摘要字符串
     */
    public static String hashBase64(String text) {
        return java.util.Base64.getEncoder().encodeToString(hash(text));
    }

    // ================================================================
    // 增量更新方法 (流式哈希)
    // ================================================================

    /**
     * 创建一个新的 SM3 摘要器实例
     * 用于增量式计算 (分块处理大文件)
     *
     * @return SM3Digest 实例
     *
     * 使用示例:
     *   SM3Digest digest = Sm3Util.newDigest();
     *   digest.update(part1, 0, part1.length);
     *   digest.update(part2, 0, part2.length);
     *   byte[] result = Sm3Util.finalizeDigest(digest);
     */
    public static SM3Digest newDigest() {
        return new SM3Digest();
    }

    /**
     * 完成 SM3 摘要计算并返回结果
     *
     * @param digest SM3Digest 实例
     * @return 32 字节的摘要值
     */
    public static byte[] finalizeDigest(SM3Digest digest) {
        byte[] result = new byte[digest.getDigestSize()];
        digest.doFinal(result, 0);
        return result;
    }

    // ================================================================
    // JCE 兼容方法
    // ================================================================

    /**
     * 获取 JCE MessageDigest 实例 (SM3)
     * 用于需要与 JCA/JCE 集成的场景
     *
     * @return MessageDigest 实例
     */
    public static MessageDigest getMessageDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM, "BC");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SM3 algorithm not available", e);
        } catch (java.security.NoSuchProviderException e) {
            // 尝试无 Provider 的方式
            try {
                return MessageDigest.getInstance(ALGORITHM);
            } catch (NoSuchAlgorithmException ex) {
                throw new RuntimeException("SM3 algorithm not available", ex);
            }
        }
    }

    // ================================================================
    // 内部方法
    // ================================================================

    /**
     * SM3 摘要计算核心实现
     */
    private static byte[] hashInternal(byte[] data, int offset, int length) {
        SM3Digest digest = new SM3Digest();
        digest.update(data, offset, length);
        byte[] result = new byte[digest.getDigestSize()];
        digest.doFinal(result, 0);
        return result;
    }

    // ================================================================
    // 工具方法
    // ================================================================

    /**
     * 将字节数组转换为十六进制字符串 (小写)
     *
     * @param bytes 字节数组
     * @return 十六进制字符串
     */
    public static String toHex(byte[] bytes) {
        return Hex.toHexString(bytes);
    }

    /**
     * 将十六进制字符串转换为字节数组
     *
     * @param hex 十六进制字符串
     * @return 字节数组
     */
    public static byte[] fromHex(String hex) {
        return Hex.decode(hex);
    }
}
