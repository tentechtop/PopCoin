package com.pop.popcoinsystem.exception;


/**
 * 当尝试处理不支持的加密货币地址类型时抛出的异常
 */
public class UnsupportedAddressException extends RuntimeException {
    private static final long serialVersionUID = 20250729L; // 使用今天的日期作为版本号

    public UnsupportedAddressException() {
        super();
    }

    public UnsupportedAddressException(String message) {
        super(message);
    }

    public UnsupportedAddressException(String message, Throwable cause) {
        super(message, cause);
    }

    public UnsupportedAddressException(Throwable cause) {
        super(cause);
    }

    protected UnsupportedAddressException(String message, Throwable cause,
                                          boolean enableSuppression,
                                          boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}