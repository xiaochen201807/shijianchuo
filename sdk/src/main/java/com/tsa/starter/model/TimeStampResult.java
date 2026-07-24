package com.tsa.starter.model;

import java.math.BigInteger;
import java.util.Base64;
import java.util.Date;

/**
 * 时间戳请求结果封装
 *
 * 封装 RFC 3161 时间戳响应的所有信息
 */
public class TimeStampResult {

    /**
     * 原始时间戳响应 (DER 编码)
     */
    private final byte[] encodedResponse;

    /**
     * 时间戳令牌 (CMS SignedData DER 编码)
     */
    private final byte[] timeStampToken;

    /**
     * TSA 生成的序列号
     */
    private final BigInteger serialNumber;

    /**
     * 时间戳生成时间 (UTC)
     */
    private final Date genTime;

    /**
     * 时间戳策略 OID
     */
    private final String policyOid;

    /**
     * 使用的摘要算法 OID
     */
    private final String hashAlgorithmOid;

    /**
     * 原始摘要值
     */
    private final byte[] messageImprint;

    /**
     * 状态码 (RFC 3161 PKIStatus)
     * 0 = granted
     * 1 = grantedWithMods
     * 2 = rejection
     * 3 = waiting
     * 4 = revocationWarning
     * 5 = revocationNotification
     */
    private final int status;

    /**
     * 状态字符串
     */
    private final String statusString;

    // 构造器
    public TimeStampResult(byte[] encodedResponse, byte[] timeStampToken,
                           BigInteger serialNumber, Date genTime,
                           String policyOid, String hashAlgorithmOid,
                           byte[] messageImprint, int status, String statusString) {
        this.encodedResponse = encodedResponse;
        this.timeStampToken = timeStampToken;
        this.serialNumber = serialNumber;
        this.genTime = genTime;
        this.policyOid = policyOid;
        this.hashAlgorithmOid = hashAlgorithmOid;
        this.messageImprint = messageImprint;
        this.status = status;
        this.statusString = statusString;
    }

    // ================================================================
    // Getters
    // ================================================================

    /**
     * 获取原始时间戳响应 (DER 编码)
     */
    public byte[] getEncodedResponse() {
        return encodedResponse;
    }

    /**
     * 获取原始时间戳响应 (Base64 编码)
     */
    public String getEncodedResponseBase64() {
        return Base64.getEncoder().encodeToString(encodedResponse);
    }

    /**
     * 获取时间戳令牌
     */
    public byte[] getTimeStampToken() {
        return timeStampToken;
    }

    /**
     * 获取时间戳令牌 (Base64 编码)
     */
    public String getTimeStampTokenBase64() {
        return Base64.getEncoder().encodeToString(timeStampToken);
    }

    /**
     * 获取序列号
     */
    public BigInteger getSerialNumber() {
        return serialNumber;
    }

    /**
     * 获取序列号 (十六进制)
     */
    public String getSerialNumberHex() {
        return serialNumber.toString(16);
    }

    /**
     * 获取生成时间
     */
    public Date getGenTime() {
        return genTime;
    }

    /**
     * 获取策略 OID
     */
    public String getPolicyOid() {
        return policyOid;
    }

    /**
     * 获取摘要算法 OID
     */
    public String getHashAlgorithmOid() {
        return hashAlgorithmOid;
    }

    /**
     * 获取消息摘要
     */
    public byte[] getMessageImprint() {
        return messageImprint;
    }

    /**
     * 获取消息摘要 (十六进制)
     */
    public String getMessageImprintHex() {
        if (messageImprint == null) return null;
        StringBuilder sb = new StringBuilder();
        for (byte b : messageImprint) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * 获取状态码
     */
    public int getStatus() {
        return status;
    }

    /**
     * 获取状态字符串
     */
    public String getStatusString() {
        return statusString;
    }

    /**
     * 是否成功
     */
    public boolean isSuccess() {
        return status == 0 || status == 1;
    }

    @Override
    public String toString() {
        return "TimeStampResult{" +
                "status=" + status +
                ", statusString='" + statusString + '\'' +
                ", serialNumber=" + getSerialNumberHex() +
                ", genTime=" + genTime +
                ", policyOid='" + policyOid + '\'' +
                ", hashAlgorithmOid='" + hashAlgorithmOid + '\'' +
                ", messageImprintHex='" + getMessageImprintHex() + '\'' +
                ", tokenSize=" + (timeStampToken != null ? timeStampToken.length : 0) + " bytes" +
                ", responseSize=" + (encodedResponse != null ? encodedResponse.length : 0) + " bytes" +
                '}';
    }
}
