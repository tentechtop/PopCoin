package com.pop.popcoinsystem.exception;


/**
 * 当检测到不支持的区块链（如创世区块哈希不匹配）时抛出的异常
 */
public class UnsupportedChainException extends Exception {

    /**
     * 构造一个空的UnsupportedChainException
     */
    public UnsupportedChainException() {
        super();
    }

    /**
     * 构造一个带有详细消息的UnsupportedChainException
     * @param message 详细错误消息
     */
    public UnsupportedChainException(String message) {
        super(message);
    }

    /**
     * 构造一个带有详细消息和原因的UnsupportedChainException
     * @param message 详细错误消息
     * @param cause 导致此异常的原因
     */
    public UnsupportedChainException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 构造一个带有原因的UnsupportedChainException
     * @param cause 导致此异常的原因
     */
    public UnsupportedChainException(Throwable cause) {
        super(cause);
    }
}
