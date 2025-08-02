package com.pop.popcoinsystem.network.protocol.message;

import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.messageData.RpcResponseData;

// RPC 响应消息（服务端 -> 客户端）
public class RpcResponseMessage extends KademliaMessage<RpcResponseData> {
    // 构造器：指定消息类型，初始化数据载体
    public RpcResponseMessage() {
        super(MessageType.RPC_RESPONSE.getCode(), new RpcResponseData());
    }

    public void setResult(Object result) {
        this.getData().setResult(result);
    }

    public void setException(Exception exception) {
        this.getData().setException(exception);
    }

    public Object getResult() {
        return this.getData().getResult();
    }

    public Exception getException() {
        return this.getData().getException();
    }
}