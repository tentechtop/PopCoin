package com.pop.popcoinsystem.network.protocol.message;

import com.pop.popcoinsystem.network.common.FindNodeResult;
import com.pop.popcoinsystem.network.protocol.MessageType;
import lombok.Builder;
import lombok.ToString;


@Builder
@ToString(callSuper = true)
public class FindNodeResponseMessage extends KademliaMessage<FindNodeResult>{

    public FindNodeResponseMessage() {
        super(MessageType.FIND_NODE_RES.getCode());
    }

}
