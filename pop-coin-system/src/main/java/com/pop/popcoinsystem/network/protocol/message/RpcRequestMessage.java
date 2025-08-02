package com.pop.popcoinsystem.network.protocol.message;

import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.messageData.RpcRequestData;

// RPC 请求消息（客户端 -> 服务端）
public class RpcRequestMessage extends KademliaMessage<RpcRequestData> {
    // 构造器：指定消息类型，初始化数据载体
    public RpcRequestMessage() {
        super(MessageType.RPC_REQUEST.getCode(), new RpcRequestData());
    }

    // 简化 set 方法（直接操作 data 字段）
    public void setServiceName(String serviceName) {
        this.getData().setServiceName(serviceName);
    }

    public void setMethodName(String methodName) {
        this.getData().setMethodName(methodName);
    }

    public void setParamTypes(Class<?>[] paramTypes) {
        this.getData().setParamTypes(paramTypes);
    }

    public void setParameters(Object[] parameters) {
        this.getData().setParameters(parameters);
    }

    public String getServiceName() {
        return this.getData().getServiceName();
    }

    public String getMethodName() {
        return this.getData().getMethodName();
    }

    public Class<?>[] getParamTypes() {
        return this.getData().getParamTypes();
    }

    public Object[] getParameters() {
        return this.getData().getParameters();
    }

    public Throwable getException() {
        return this.getData().getException();
    }
}

