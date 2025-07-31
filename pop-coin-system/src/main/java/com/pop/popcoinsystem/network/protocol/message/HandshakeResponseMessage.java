package com.pop.popcoinsystem.network.protocol.message;

import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.Builder;
import lombok.ToString;


@ToString(callSuper = true)
public class HandshakeResponseMessage extends KademliaMessage<ExternalNodeInfo>{

    // 添加无参构造函数
    public HandshakeResponseMessage() {
        super(MessageType.HANDSHAKE_RES.getCode());
    }
    public HandshakeResponseMessage(ExternalNodeInfo  node) {
        super(MessageType.HANDSHAKE_RES.getCode());
        setData(node);
    }


    public static byte[] serialize(HandshakeResponseMessage message) {
        return SerializeUtils.serialize(message);
    }

    public static HandshakeResponseMessage deSerialize(byte[] bytes) {
        return (HandshakeResponseMessage)SerializeUtils.deSerialize(bytes);
    }
}