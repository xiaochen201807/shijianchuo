package com.shineyue.tsa;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TSA Spring Boot Starter 配置属性
 *
 * 在 application.yml 中配置示例:
 *
 * tsa:
 *   enabled: true                   # 是否启用 SDK (默认 false, 需显式开启)
 *   url: http://localhost:8080/tsa
 *   connect-timeout: 5000
 *   read-timeout: 30000
 *   policy-oid: 1.2.3.4.1
 *   cert-req: true
 *   hash-algorithm: SM3
 *   gofastdfs-store: http://192.168.1.10:8080
 */
@ConfigurationProperties(prefix = "tsa")
public class TsaProperties {

    /**
     * 是否启用 TSA SDK 自动配置
     * 默认 false, 需显式配置 tsa.enabled=true 才会创建 TsaClient Bean
     */
    private boolean enabled = false;

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

    /**
     * GoFastDFS 文件存储服务地址 (远端文件的下载前缀, 包含 IP 与端口)
     * 用于 timestampRemoteFile / verifyRemoteFile 远端文件打时间戳/验证,
     * 实际请求地址 = gofastdfsStore + 传入的 filePath 参数
     * 例如: http://192.168.1.10:8080
     */
    private String gofastdfsStore;

    // --- Getters & Setters ---

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

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

    public String getGofastdfsStore() {
        return gofastdfsStore;
    }

    public void setGofastdfsStore(String gofastdfsStore) {
        this.gofastdfsStore = gofastdfsStore;
    }

    @Override
    public String toString() {
        return "TsaProperties{" +
                "enabled=" + enabled +
                ", url='" + url + '\'' +
                ", connectTimeout=" + connectTimeout +
                ", readTimeout=" + readTimeout +
                ", policyOid='" + policyOid + '\'' +
                ", certReq=" + certReq +
                ", hashAlgorithm='" + hashAlgorithm + '\'' +
                ", autoRegisterProvider=" + autoRegisterProvider +
                ", gofastdfsStore='" + gofastdfsStore + '\'' +
                '}';
    }
}
