package com.pop.popcoinsystem.network.protocol.message;

import com.pop.popcoinsystem.network.protocol.MessageType;
import lombok.Builder;
import lombok.ToString;

import java.io.Serializable;
import java.math.BigInteger;
@Builder
@ToString(callSuper = true)
public class FindNodeRequestMessage extends KademliaMessage<BigInteger>{

    public FindNodeRequestMessage() {
        super(MessageType.FIND_NODE_REQ.getCode());
    }

    public BigInteger getDestinationId(){
        return this.getData();
    }

}
