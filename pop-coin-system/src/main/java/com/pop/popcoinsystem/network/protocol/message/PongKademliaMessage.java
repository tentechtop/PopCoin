package com.pop.popcoinsystem.network.protocol.message;

import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.util.SerializeUtils;
import io.netty.channel.Channel;
import lombok.ToString;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ToString(callSuper = true)
public class PongKademliaMessage extends KademliaMessage<Serializable>{

    public PongKademliaMessage() {
        super(MessageType.PING);
        setData("PONG");
    }

    public static byte[] serialize(PongKademliaMessage message) {
        return SerializeUtils.serialize(message);
    }

    public static KademliaMessage deSerialize(byte[] bytes) {
        return (PongKademliaMessage)SerializeUtils.deSerialize(bytes);
    }












}
