package com.shineyue.tsa;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TSA Spring Boot Starter 配置属性
 *
 * 在 application.yml 中配置示例:
 *
 * tsa:
 *   url: http://localhost:8080/tsa
 *   connect-timeout: 5000
 *   read-timeout: 30000
 *   policy-oid: 1.2.3.4.1
 *   cert-req: true
 *   hash-algorithm: SM3
 */
@ConfigurationProperties(prefix = "tsa")
public class TsaProperties {

    /**
     * TSA 服务器 URL (必须)
     * 例如: http://localhost:8080/tsa
     */
    private String url = "http://localhost:8080/tsa";

    /**
     * 连接超时 (毫秒)
     */
    private int connectTimeout = 5000;

    /**
     * 读取超时 (毫秒)
     */
    private int readTimeout = 30000;

    /**
     * TSA 策略 OID (可选，如果服务器有默认策略)
     */
    private String policyOid = "1.2.3.4.1";

    /**
     * 是否在响应中请求 TSA 证书
     */
    private boolean certReq = true;

    /**
     * 摘要算法 (默认 SM3 国密算法)
     */
    private String hashAlgorithm = "SM3";

    /**
     * 是否自动注册 BouncyCastle Provider
     */
    private boolean autoRegisterProvider = true;

    // --- Getters & Setters ---

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public int getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }

    public String getPolicyOid() {
        return policyOid;
    }

    public void setPolicyOid(String policyOid) {
        this.policyOid = policyOid;
    }

    public boolean isCertReq() {
        return certReq;
    }

    public void setCertReq(boolean certReq) {
        this.certReq = certReq;
    }

    public String getHashAlgorithm() {
        return hashAlgorithm;
    }

    public void setHashAlgorithm(String hashAlgorithm) {
        this.hashAlgorithm = hashAlgorithm;
    }

    public boolean isAutoRegisterProvider() {
        return autoRegisterProvider;
    }

    public void setAutoRegisterProvider(boolean autoRegisterProvider) {
        this.autoRegisterProvider = autoRegisterProvider;
    }

    @Override
    public String toString() {
        return "TsaProperties{" +
                "url='" + url + '\'' +
                ", connectTimeout=" + connectTimeout +
                ", readTimeout=" + readTimeout +
                ", policyOid='" + policyOid + '\'' +
                ", certReq=" + certReq +
                ", hashAlgorithm='" + hashAlgorithm + '\'' +
                ", autoRegisterProvider=" + autoRegisterProvider +
                '}';
    }
}
