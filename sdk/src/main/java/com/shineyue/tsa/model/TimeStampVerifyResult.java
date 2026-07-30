package com.shineyue.tsa.model;

import java.util.Date;

/**
 * 时间戳验证结果封装
 *
 * 封装验证时间戳令牌后的所有验证信息
 */
public class TimeStampVerifyResult {

    /**
     * 验证是否通过（签名有效且摘要匹配）
     */
    private final boolean valid;

    /**
     * 签名验证是否通过
     */
    private final boolean signatureValid;

    /**
     * 摘要是否匹配
     */
    private final boolean hashMatch;

    /**
     * 签名证书主题
     */
    private final String certSubject;

    /**
     * 签名证书过期时间
     */
    private final Date certExpiry;

    /**
     * 期望的摘要值（根据原始数据计算的 SM3 摘要）
     */
    private final String expectedHashHex;

    /**
     * Token 中包含的摘要值
     */
    private final String tokenHashHex;

    /**
     * 时间戳序列号（十六进制）
     */
    private final String serialNumber;

    /**
     * 时间戳生成时间
     */
    private final Date genTime;

    /**
     * 时间戳策略 OID
     */
    private final String policyOid;

    public TimeStampVerifyResult(boolean valid, boolean signatureValid, boolean hashMatch,
                                  String certSubject, Date certExpiry,
                                  String expectedHashHex, String tokenHashHex,
                                  String serialNumber, Date genTime, String policyOid) {
        this.valid = valid;
        this.signatureValid = signatureValid;
        this.hashMatch = hashMatch;
        this.certSubject = certSubject;
        this.certExpiry = certExpiry;
        this.expectedHashHex = expectedHashHex;
        this.tokenHashHex = tokenHashHex;
        this.serialNumber = serialNumber;
        this.genTime = genTime;
        this.policyOid = policyOid;
    }

    // ================================================================
    // Getters
    // ================================================================

    public boolean isValid() {
        return valid;
    }

    public boolean isSignatureValid() {
        return signatureValid;
    }

    public boolean isHashMatch() {
        return hashMatch;
    }

    public String getCertSubject() {
        return certSubject;
    }

    public Date getCertExpiry() {
        return certExpiry;
    }

    public String getExpectedHashHex() {
        return expectedHashHex;
    }

    public String getTokenHashHex() {
        return tokenHashHex;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public Date getGenTime() {
        return genTime;
    }

    public String getPolicyOid() {
        return policyOid;
    }

    @Override
    public String toString() {
        return "TimeStampVerifyResult{" +
                "valid=" + valid +
                ", signatureValid=" + signatureValid +
                ", hashMatch=" + hashMatch +
                ", certSubject='" + certSubject + '\'' +
                ", certExpiry=" + certExpiry +
                ", serialNumber='" + serialNumber + '\'' +
                ", genTime=" + genTime +
                ", policyOid='" + policyOid + '\'' +
                '}';
    }
}
