package com.pop.popcoinsystem.network.protocol.message;

import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.network.protocol.messageData.Handshake;
import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.ToString;


@ToString(callSuper = true)
public class HandshakeResponseMessage extends KademliaMessage<Handshake>{

    // 添加无参构造函数
    public HandshakeResponseMessage() {
        super(MessageType.HANDSHAKE_RES.getCode());
    }
    public HandshakeResponseMessage(Handshake  handshake) {
        super(MessageType.HANDSHAKE_RES.getCode());
        setData(handshake);
    }


    public static byte[] serialize(HandshakeResponseMessage message) {
        return SerializeUtils.serialize(message);
    }

    public static HandshakeResponseMessage deSerialize(byte[] bytes) {
        return (HandshakeResponseMessage)SerializeUtils.deSerialize(bytes);
    }
}