package com.pop.popcoinsystem.network.protocol.message;

import com.pop.popcoinsystem.network.protocol.MessageType;
import lombok.ToString;

@ToString(callSuper = true)
public class FindForkPointResponseMessage extends KademliaMessage<byte[]>{
    public FindForkPointResponseMessage() {
        super(MessageType.GET_FORK_POINT_RES.getCode());
    }

    public FindForkPointResponseMessage(byte[] data) {
        super(MessageType.GET_FORK_POINT_RES.getCode());
        setData(data);
    }
}
