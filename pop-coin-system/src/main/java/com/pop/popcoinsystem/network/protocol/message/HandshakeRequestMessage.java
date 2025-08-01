package com.pop.popcoinsystem.network.protocol.message;

import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.network.protocol.message.content.Handshake;
import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.Builder;
import lombok.ToString;

import java.math.BigInteger;


@ToString(callSuper = true)
public class HandshakeRequestMessage extends KademliaMessage<Handshake>{

    // 添加无参构造函数
    public HandshakeRequestMessage() {
        super(MessageType.HANDSHAKE_REQ.getCode());
    }

    public HandshakeRequestMessage(Handshake handshake) {
        super(MessageType.HANDSHAKE_REQ.getCode());
        setData(handshake);
    }

    public static byte[] serialize(HandshakeRequestMessage message) {
        return SerializeUtils.serialize(message);
    }

    public static HandshakeRequestMessage deSerialize(byte[] bytes) {
        return (HandshakeRequestMessage)SerializeUtils.deSerialize(bytes);
    }
}
