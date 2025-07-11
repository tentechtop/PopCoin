package com.pop.popcoinsystem.network.protocol.message;

import com.pop.popcoinsystem.network.protocol.MessageType;
import lombok.Builder;
import lombok.ToString;

import java.io.Serializable;
@Builder
@ToString(callSuper = true)
public class EmptyKademliaMessage extends KademliaMessage<Serializable>{
    public EmptyKademliaMessage() {
        super(MessageType.EMPTY.getCode());
    }

}
