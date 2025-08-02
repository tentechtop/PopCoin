package com.pop.popcoinsystem.network.protocol.messageData;

import lombok.Data;

import java.io.Serializable;

// RPC 请求数据（包含服务调用信息，实现 Serializable）
@Data
public class RpcRequestData implements Serializable {
    // 必须显式声明序列化版本号，避免序列化兼容性问题
    private static final long serialVersionUID = 1L;
    private String serviceName;       // 服务接口名
    private String methodName;        // 方法名
    private Class<?>[] paramTypes;    // 参数类型（Class<?> 本身可序列化）
    private Object[] parameters;      // 参数值（需确保参数对象可序列化）
    private long requestId;         // 请求唯一标识

    private Exception exception;      // 异常信息（Exception 实现了 Serializable）

}
