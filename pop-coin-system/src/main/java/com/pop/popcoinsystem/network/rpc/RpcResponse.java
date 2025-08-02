package com.pop.popcoinsystem.network.rpc;

import lombok.Data;

// RPC 响应消息
@Data
public class RpcResponse {
    private String requestId;      // 添加请求ID（与对应的请求保持一致）
    private Object result;         // 方法返回结果
    private Exception exception;   // 异常信息（如果有）
    // getter/setter
}