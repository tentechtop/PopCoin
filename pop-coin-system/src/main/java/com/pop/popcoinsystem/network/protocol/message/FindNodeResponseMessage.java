package com.pop.popcoinsystem.network.protocol.message;

import com.pop.popcoinsystem.network.common.FindNodeResult;
import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.Builder;
import lombok.ToString;


@Builder
@ToString(callSuper = true)
public class FindNodeResponseMessage extends KademliaMessage<FindNodeResult>{


    public FindNodeResponseMessage() {
        super(MessageType.FIND_NODE_RES.getCode());
        setData(null);
    }



    public static byte[] serialize(FindNodeResponseMessage message) {
        return SerializeUtils.serialize(message);
    }

    public static FindNodeResponseMessage deSerialize(byte[] bytes) {
        return (FindNodeResponseMessage)SerializeUtils.deSerialize(bytes);
    }



}
