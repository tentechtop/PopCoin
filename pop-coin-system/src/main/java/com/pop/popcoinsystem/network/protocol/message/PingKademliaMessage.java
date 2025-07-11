package com.pop.popcoinsystem.network.protocol.message;

import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.ToString;

import java.io.Serializable;
@ToString(callSuper = true)
public class PingKademliaMessage extends KademliaMessage<Serializable>{

    public PingKademliaMessage() {
        super(MessageType.PING);
        setData("PING");
    }

    public static byte[] serialize(PingKademliaMessage message) {
        return SerializeUtils.serialize(message);
    }

    public static KademliaMessage deSerialize(byte[] bytes) {
        return (PingKademliaMessage)SerializeUtils.deSerialize(bytes);
    }
}
