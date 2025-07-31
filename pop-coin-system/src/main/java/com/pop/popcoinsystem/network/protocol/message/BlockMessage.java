package com.pop.popcoinsystem.network.protocol.message;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.Builder;
import lombok.ToString;

@Builder
@ToString(callSuper = true)
public class BlockMessage extends KademliaMessage<Block>{

    public BlockMessage() {
        super(MessageType.BLOCK.getCode());
        setData(null);
    }

    public static byte[] serialize(BlockMessage message) {
        return SerializeUtils.serialize(message);
    }

    public static KademliaMessage deSerialize(byte[] bytes) {
        return (BlockMessage)SerializeUtils.deSerialize(bytes);
    }
}
