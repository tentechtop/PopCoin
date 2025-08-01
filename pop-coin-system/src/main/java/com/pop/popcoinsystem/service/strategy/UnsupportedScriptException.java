package com.pop.popcoinsystem.service.strategy;

/**
 * 当遇到不支持的脚本类型时抛出的异常
 * 用于脚本验证过程中，处理未实现或不支持的脚本类型（如未定义的P2xx脚本）
 */
public class UnsupportedScriptException extends RuntimeException {

    // 序列化版本号，确保异常序列化兼容性
    private static final long serialVersionUID = 1L;

    /**
     * 无参构造方法
     */
    public UnsupportedScriptException() {
        super();
    }

    /**
     * 带错误消息的构造方法
     * @param message 错误详情（如不支持的脚本类型编码、名称等）
     */
    public UnsupportedScriptException(String message) {
        super(message);
    }

    /**
     * 带错误消息和根因的构造方法
     * @param message 错误详情
     * @param cause 引发该异常的底层异常（如解析脚本时的异常）
     */
    public UnsupportedScriptException(String message, Throwable cause) {
        super(message, cause);
    }
}