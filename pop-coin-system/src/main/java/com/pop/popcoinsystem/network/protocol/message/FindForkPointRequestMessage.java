package com.pop.popcoinsystem.network.protocol.message;

import com.pop.popcoinsystem.network.protocol.MessageType;
import lombok.ToString;

@ToString(callSuper = true)
public class FindForkPointRequestMessage extends KademliaMessage<byte[]>{
    public FindForkPointRequestMessage() {
        super(MessageType.GET_FORK_POINT_REQ.getCode());
    }

    public FindForkPointRequestMessage(byte[] data) {
        super(MessageType.GET_FORK_POINT_REQ.getCode());
        setData(data);
    }
}
