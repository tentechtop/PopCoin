package com.pop.popcoinsystem.network.protocol.message;

import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.Builder;
import lombok.ToString;

import java.io.Serializable;
@Builder
@ToString(callSuper = true)
public class PingKademliaMessage extends KademliaMessage<String>{

    public PingKademliaMessage() {
        super(MessageType.PING.getCode());
        setData(null);
    }

    public static byte[] serialize(PingKademliaMessage message) {
        return SerializeUtils.serialize(message);
    }

    public static KademliaMessage deSerialize(byte[] bytes) {
        return (PingKademliaMessage)SerializeUtils.deSerialize(bytes);
    }
}
