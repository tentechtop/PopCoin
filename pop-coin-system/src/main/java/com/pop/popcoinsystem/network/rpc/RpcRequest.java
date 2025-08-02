package com.pop.popcoinsystem.network.rpc;

import lombok.Data;

// RPC 请求消息
@Data
public class RpcRequest {
    private String requestId;      // 添加请求ID
    private String interfaceName;  // 接口名
    private String methodName;     // 方法名
    private Class<?>[] paramTypes; // 参数类型
    private Object[] parameters;   // 参数值
    // getter/setter
}