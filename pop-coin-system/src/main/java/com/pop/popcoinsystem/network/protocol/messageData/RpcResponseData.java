package com.pop.popcoinsystem.network.protocol.messageData;

import lombok.Data;

import java.io.Serializable;

// RPC 响应数据（包含调用结果，实现 Serializable）
@Data
public class RpcResponseData implements Serializable {
    private static final long serialVersionUID = 1L;
    private long requestId;         // 关联的请求 ID
    private Object result;            // 方法返回值（需可序列化）
    private Exception exception;      // 异常信息（Exception 实现了 Serializable）


}