package com.tsa.demo.controller;

import com.shineyue.tsa.TsaClient;
import com.shineyue.tsa.exception.TsaException;
import com.shineyue.tsa.model.TimeStampResult;
import com.shineyue.tsa.model.TimeStampVerifyResult;
import com.shineyue.tsa.sm2.Sm2Util;
import com.shineyue.tsa.sm3.Sm3Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * TSA Demo REST Controller
 *
 * 提供以下 API 接口用于测试:
 *
 * 1. SM3 摘要测试
 *    POST /api/sm3/hash
 *    GET  /api/sm3/hash?text=Hello
 *
 * 2. SM2 签名/验证测试
 *    POST /api/sm2/sign
 *    POST /api/sm2/verify
 *    POST /api/sm2/encrypt
 *    POST /api/sm2/decrypt
 *    GET  /api/sm2/keypair
 *
 * 3. TSA 时间戳请求测试
 *    POST /api/tsa/timestamp
 *    POST /api/tsa/timestamp/text
 *    POST /api/tsa/timestamp/sm3
 *
 * 4. TSA 时间戳验证
 *    POST /api/tsa/verify
 */
@RestController
@RequestMapping("/api")
public class TsaDemoController {

    private static final Logger logger = LoggerFactory.getLogger(TsaDemoController.class);

    @Autowired
    private TsaClient tsaClient;

    // ================================================================
    // 1. SM3 摘要 API
    // ================================================================

    /**
     * 计算字符串的 SM3 摘要
     *
     * GET /api/sm3/hash?text=Hello
     */
    @GetMapping("/sm3/hash")
    public ResponseEntity<Map<String, Object>> sm3HashGet(@RequestParam String text) {
        String hashHex = Sm3Util.hashHex(text);
        String hashBase64 = Sm3Util.hashBase64(text);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("algorithm", "SM3");
        result.put("input", text);
        result.put("hashHex", hashHex);
        result.put("hashBase64", hashBase64);
        result.put("digestSize", Sm3Util.DIGEST_SIZE);

        return ResponseEntity.ok(result);
    }

    /**
     * 计算数据的 SM3 摘要 (POST)
     *
     * POST /api/sm3/hash
     * Body: { "text": "Hello, TSA!" }
     */
    @PostMapping("/sm3/hash")
    public ResponseEntity<Map<String, Object>> sm3HashPost(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        if (text == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'text' field"));
        }
        return sm3HashGet(text);
    }

    // ================================================================
    // 2. SM2 签名/加密 API
    // ================================================================

    /**
     * 生成 SM2 密钥对
     *
     * GET /api/sm2/keypair
     */
    @GetMapping("/sm2/keypair")
    public ResponseEntity<Map<String, Object>> generateKeyPair() {
        KeyPair keyPair = Sm2Util.generateKeyPair();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("algorithm", "SM2");
        result.put("curve", Sm2Util.CURVE_NAME);
        result.put("privateKeyHex", Sm2Util.privateKeyToHex(keyPair.getPrivate()));
        result.put("publicKeyHex", Sm2Util.publicKeyToHex(keyPair.getPublic()));
        result.put("publicKeyCompressedHex", Sm2Util.publicKeyToHexCompressed(keyPair.getPublic()));

        return ResponseEntity.ok(result);
    }

    /**
     * SM2 数字签名
     *
     * POST /api/sm2/sign
     * Body: {
     *   "text": "Hello, TSA!",
     *   "privateKeyHex": "..."
     * }
     *
     * 如果不提供 privateKeyHex，会自动生成密钥对
     */
    @PostMapping("/sm2/sign")
    public ResponseEntity<Map<String, Object>> sm2Sign(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        if (text == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'text' field"));
        }

        PrivateKey privateKey;
        String publicKeyHex;
        String privateKeyHexOut;

        if (body.containsKey("privateKeyHex") && body.get("privateKeyHex") != null
                && !body.get("privateKeyHex").isBlank()) {
            // 使用调用方提供的私钥；公钥需配套传入或由曲线点推导不可直接反推，
            // 若同时提供 publicKeyHex 则原样返回，否则仅返回签名
            privateKey = Sm2Util.privateKeyFromHex(body.get("privateKeyHex"));
            privateKeyHexOut = body.get("privateKeyHex");
            publicKeyHex = body.get("publicKeyHex");
        } else {
            KeyPair keyPair = Sm2Util.generateKeyPair();
            privateKey = keyPair.getPrivate();
            privateKeyHexOut = Sm2Util.privateKeyToHex(keyPair.getPrivate());
            publicKeyHex = Sm2Util.publicKeyToHex(keyPair.getPublic());
        }

        try {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            byte[] signature = Sm2Util.sign(data, privateKey);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("algorithm", "SM3withSM2");
            result.put("input", text);
            result.put("signatureBase64", Base64.getEncoder().encodeToString(signature));
            result.put("privateKeyHex", privateKeyHexOut);
            if (publicKeyHex != null) {
                result.put("publicKeyHex", publicKeyHex);
            }

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("SM2 sign failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * SM2 签名验证
     *
     * POST /api/sm2/verify
     * Body: {
     *   "text": "Hello, TSA!",
     *   "signatureBase64": "...",
     *   "publicKeyHex": "..."
     * }
     */
    @PostMapping("/sm2/verify")
    public ResponseEntity<Map<String, Object>> sm2Verify(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        String signatureBase64 = body.get("signatureBase64");
        String publicKeyHex = body.get("publicKeyHex");

        if (text == null || signatureBase64 == null || publicKeyHex == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Missing required fields: text, signatureBase64, publicKeyHex"
            ));
        }

        try {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            byte[] signature = Base64.getDecoder().decode(signatureBase64);
            PublicKey publicKey = Sm2Util.publicKeyFromHex(publicKeyHex);

            boolean valid = Sm2Util.verify(data, signature, publicKey);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("algorithm", "SM3withSM2");
            result.put("input", text);
            result.put("valid", valid);

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("SM2 verify failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * SM2 加密
     *
     * POST /api/sm2/encrypt
     * Body: {
     *   "text": "Secret message",
     *   "publicKeyHex": "..."
     * }
     */
    @PostMapping("/sm2/encrypt")
    public ResponseEntity<Map<String, Object>> sm2Encrypt(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        if (text == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'text' field"));
        }

        PublicKey publicKey;
        String privateKeyHex;

        if (body.containsKey("publicKeyHex")) {
            publicKey = Sm2Util.publicKeyFromHex(body.get("publicKeyHex"));
            privateKeyHex = null;
        } else {
            KeyPair keyPair = Sm2Util.generateKeyPair();
            publicKey = keyPair.getPublic();
            privateKeyHex = Sm2Util.privateKeyToHex(keyPair.getPrivate());
        }

        try {
            byte[] plaintext = text.getBytes(StandardCharsets.UTF_8);
            byte[] ciphertext = Sm2Util.encrypt(plaintext, publicKey);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("algorithm", "SM2");
            result.put("input", text);
            result.put("ciphertextBase64", Base64.getEncoder().encodeToString(ciphertext));
            if (privateKeyHex != null) {
                result.put("privateKeyHex", privateKeyHex);
                result.put("note", "Auto-generated keypair. Use privateKeyHex to decrypt.");
            }

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("SM2 encrypt failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * SM2 解密
     *
     * POST /api/sm2/decrypt
     * Body: {
     *   "ciphertextBase64": "...",
     *   "privateKeyHex": "..."
     * }
     */
    @PostMapping("/sm2/decrypt")
    public ResponseEntity<Map<String, Object>> sm2Decrypt(@RequestBody Map<String, String> body) {
        String ciphertextBase64 = body.get("ciphertextBase64");
        String privateKeyHex = body.get("privateKeyHex");

        if (ciphertextBase64 == null || privateKeyHex == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Missing required fields: ciphertextBase64, privateKeyHex"
            ));
        }

        try {
            byte[] ciphertext = Base64.getDecoder().decode(ciphertextBase64);
            PrivateKey privateKey = Sm2Util.privateKeyFromHex(privateKeyHex);
            byte[] plaintext = Sm2Util.decrypt(ciphertext, privateKey);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("algorithm", "SM2");
            result.put("plaintext", new String(plaintext, StandardCharsets.UTF_8));

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("SM2 decrypt failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ================================================================
    // 3. TSA 时间戳请求 API
    // ================================================================

    /**
     * 对文本数据请求时间戳
     *
     * POST /api/tsa/timestamp/text
     * Body: { "text": "Hello, TSA!" }
     */
    @PostMapping("/tsa/timestamp/text")
    public ResponseEntity<Map<String, Object>> timestampText(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        if (text == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'text' field"));
        }

        try {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            TimeStampResult result = tsaClient.timestamp(data);

            return ResponseEntity.ok(buildTimestampResponse(result, text));
        } catch (TsaException e) {
            logger.error("Timestamp request failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getMessage(),
                    "errorCode", e.getErrorCode()
            ));
        }
    }

    /**
     * 对 Base64 编码数据请求时间戳
     *
     * POST /api/tsa/timestamp
     * Body: { "dataBase64": "SGVsbG8sIFRTQQ==" }
     */
    @PostMapping("/tsa/timestamp")
    public ResponseEntity<Map<String, Object>> timestampData(@RequestBody Map<String, String> body) {
        String dataBase64 = body.get("dataBase64");
        if (dataBase64 == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'dataBase64' field"));
        }

        try {
            byte[] data = Base64.getDecoder().decode(dataBase64);
            TimeStampResult result = tsaClient.timestamp(data);

            return ResponseEntity.ok(buildTimestampResponse(result, null));
        } catch (TsaException e) {
            logger.error("Timestamp request failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getMessage(),
                    "errorCode", e.getErrorCode()
            ));
        }
    }

    /**
     * 对数据先计算 SM3 摘要，再请求时间戳
     *
     * POST /api/tsa/timestamp/sm3
     * Body: { "text": "Hello, TSA!" }
     */
    @PostMapping("/tsa/timestamp/sm3")
    public ResponseEntity<Map<String, Object>> timestampWithSm3(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        if (text == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'text' field"));
        }

        try {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            byte[] hash = Sm3Util.hash(data);

            logger.info("SM3 hash of input: {}", Sm3Util.toHex(hash));

            TimeStampResult result = tsaClient.timestampWithSm3Hash(hash);

            Map<String, Object> response = buildTimestampResponse(result, text);
            response.put("sm3HashHex", Sm3Util.toHex(hash));

            return ResponseEntity.ok(response);
        } catch (TsaException e) {
            logger.error("Timestamp request failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getMessage(),
                    "errorCode", e.getErrorCode()
            ));
        }
    }

    /**
     * 验证时间戳令牌
     *
     * 请求体:
     *   text: 原始文本 (必须)
     *   responseBase64: /tsa/timestamp/text 返回的 responseBase64 字段 (必须)
     *
     * 自动从 Token 内部提取签名证书进行验证，无需调用方传入任何证书。
     * 无论证书是否续期/更换，都能自动识别并验证。
     *
     * 验证内容:
     *   1. 令牌签名是否有效 (使用 Token 内嵌的 TSA 证书)
     *   2. 令牌中的摘要是否与原始文本的 SM3 摘要一致 (数据完整性)
     */
    @PostMapping("/tsa/verify")
    public ResponseEntity<Map<String, Object>> verifyTimestamp(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        String responseBase64 = body.get("responseBase64");
        if (text == null || responseBase64 == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing required fields: text, responseBase64"));
        }
        try {
            // 使用 SDK 的验证方法，自动从 Token 内部提取证书
            TimeStampVerifyResult verifyResult = tsaClient.verifyTimestamp(text, responseBase64);

            // 组装响应
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("valid", verifyResult.isValid());
            result.put("signatureValid", verifyResult.isSignatureValid());
            result.put("hashMatch", verifyResult.isHashMatch());
            result.put("certSubject", verifyResult.getCertSubject());
            result.put("certExpiry", verifyResult.getCertExpiry().toString());
            result.put("expectedHashHex", verifyResult.getExpectedHashHex());
            result.put("tokenHashHex", verifyResult.getTokenHashHex());
            result.put("serialNumber", verifyResult.getSerialNumber());
            result.put("genTime", verifyResult.getGenTime().toString());
            result.put("policyOid", verifyResult.getPolicyOid());
            result.put("input", text);

            return ResponseEntity.ok(result);

        } catch (TsaException e) {
            logger.error("Timestamp verification failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Timestamp verification failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ================================================================
    // 辅助方法
    // ================================================================

    private Map<String, Object> buildTimestampResponse(TimeStampResult result, String input) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", result.isSuccess());
        response.put("status", result.getStatus());
        response.put("statusString", result.getStatusString());
        response.put("serialNumber", result.getSerialNumberHex());
        response.put("genTime", result.getGenTime() != null ? result.getGenTime().toString() : null);
        response.put("policyOid", result.getPolicyOid());
        response.put("hashAlgorithmOid", result.getHashAlgorithmOid());
        response.put("messageImprintHex", result.getMessageImprintHex());
        response.put("tokenBase64", result.getTimeStampTokenBase64());
        response.put("responseBase64", result.getEncodedResponseBase64());
        response.put("tokenSize", result.getTimeStampToken() != null ? result.getTimeStampToken().length : 0);
        if (input != null) {
            response.put("input", input);
        }
        return response;
    }
}
