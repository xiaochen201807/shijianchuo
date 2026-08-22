package com.tsa.server;

import com.shineyue.tsa.TsaSigner;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.FileInputStream;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * RFC 3161 时间戳服务端点
 *
 * 接收 TimeStampReq (DER)，返回 TimeStampResp (DER)
 * 使用 BouncyCastle SM3withSM2 直接签名，无 CGI 开销
 */
@RestController
public class TsaEndpoint {

    private static final Logger logger = LoggerFactory.getLogger(TsaEndpoint.class);

    private static final String CONTENT_TYPE_QUERY = "application/timestamp-query";
    private static final String CONTENT_TYPE_REPLY = "application/timestamp-reply";

    @Value("${tsa.server.cert-path:/etc/tsa/certs/tsacert.pem}")
    private String certPath;

    @Value("${tsa.server.key-path:/etc/tsa/certs/tsakey.pem}")
    private String keyPath;

    @Value("${tsa.server.chain-cert-path:/etc/tsa/certs/cacert.pem}")
    private String chainCertPath;

    @Value("${tsa.server.policy-oid:1.2.3.4.1}")
    private String policyOid;

    private TsaSigner signer;

    @PostConstruct
    public void init() {
        try {
            logger.info("Loading TSA certificate from: {}", certPath);
            X509Certificate cert;
            try (FileInputStream fis = new FileInputStream(certPath)) {
                cert = TsaSigner.loadCertificate(fis);
            }

            logger.info("Loading TSA private key from: {}", keyPath);
            PrivateKey key;
            try (FileInputStream fis = new FileInputStream(keyPath)) {
                key = TsaSigner.loadPrivateKey(fis);
            }

            // 加载证书链 (CA 证书)
            List<X509Certificate> certChain = new ArrayList<>();
            if (chainCertPath != null && !chainCertPath.isEmpty()) {
                logger.info("Loading CA chain certificate from: {}", chainCertPath);
                try (FileInputStream fis = new FileInputStream(chainCertPath)) {
                    certChain.add(TsaSigner.loadCertificate(fis));
                }
            }

            signer = new TsaSigner(cert, key, policyOid, certChain);

            logger.info("TSA Server initialized: cert={}, policy={}",
                    cert.getSubjectX500Principal().getName(), policyOid);

        } catch (Exception e) {
            logger.error("Failed to initialize TSA Server", e);
            throw new RuntimeException("TSA Server initialization failed", e);
        }
    }

    /**
     * RFC 3161 时间戳签名端点
     *
     * POST /tsa
     * Content-Type: application/timestamp-query
     * Accept: application/timestamp-reply
     *
     * @param request TimeStampReq DER 编码
     * @return TimeStampResp DER 编码
     */
    @PostMapping(value = "/tsa",
            consumes = CONTENT_TYPE_QUERY,
            produces = CONTENT_TYPE_REPLY)
    public ResponseEntity<byte[]> timestamp(@RequestBody byte[] request) {
        try {
            byte[] response = signer.sign(request);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(CONTENT_TYPE_REPLY))
                    .body(response);

        } catch (Exception e) {
            logger.error("Timestamp signing failed", e);
            return ResponseEntity.internalServerError()
                    .body(("Error: " + e.getMessage()).getBytes());
        }
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        if (signer == null) {
            return ResponseEntity.status(503).body("TSA Server not initialized");
        }
        return ResponseEntity.ok("OK");
    }
}
