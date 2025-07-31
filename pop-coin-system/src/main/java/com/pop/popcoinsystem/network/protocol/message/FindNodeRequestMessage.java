package com.pop.popcoinsystem.network.protocol.message;

import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.Builder;
import lombok.ToString;

import java.io.Serializable;
import java.math.BigInteger;
@Builder
@ToString(callSuper = true)
public class FindNodeRequestMessage extends KademliaMessage<BigInteger>{

    public FindNodeRequestMessage() {
        super(MessageType.FIND_NODE_REQ.getCode());
        this.setData(null);
    }

    public BigInteger getDestinationId(){
        return this.getData();
    }


    public static byte[] serialize(FindNodeRequestMessage message) {
        return SerializeUtils.serialize(message);
    }

    public static FindNodeRequestMessage deSerialize(byte[] bytes) {
        return (FindNodeRequestMessage)SerializeUtils.deSerialize(bytes);
    }

}
