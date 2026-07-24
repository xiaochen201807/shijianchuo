package com.tsa.starter.exception;

/**
 * TSA 操作异常
 * 用于封装时间戳请求、SM2/SM3 操作中的异常
 */
public class TsaException extends RuntimeException {

    /**
     * 错误码
     */
    private final String errorCode;

    /**
     * 构造异常
     *
     * @param message 错误消息
     */
    public TsaException(String message) {
        super(message);
        this.errorCode = "TSA_ERROR";
    }

    /**
     * 构造异常
     *
     * @param message 错误消息
     * @param cause   原始异常
     */
    public TsaException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "TSA_ERROR";
    }

    /**
     * 构造异常
     *
     * @param errorCode 错误码
     * @param message   错误消息
     */
    public TsaException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 构造异常
     *
     * @param errorCode 错误码
     * @param message   错误消息
     * @param cause      原始异常
     */
    public TsaException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 获取错误码
     */
    public String getErrorCode() {
        return errorCode;
    }

    @Override
    public String toString() {
        return "TsaException{" +
                "errorCode='" + errorCode + '\'' +
                ", message='" + getMessage() + '\'' +
                '}';
    }
}
