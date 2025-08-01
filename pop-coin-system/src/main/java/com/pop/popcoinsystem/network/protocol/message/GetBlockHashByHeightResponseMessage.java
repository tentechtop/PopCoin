package com.pop.popcoinsystem.network.protocol.message;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.network.protocol.MessageType;
import lombok.Builder;
import lombok.ToString;

@Builder
@ToString(callSuper = true)
public class GetBlockHashByHeightResponseMessage extends KademliaMessage<Block>{

    // 添加无参构造函数
    public GetBlockHashByHeightResponseMessage() {
        super(MessageType.GET_BLOCK_BY_HEIGHT_RES.getCode());
    }

    public GetBlockHashByHeightResponseMessage(Block data) {
        super(MessageType.GET_BLOCK_BY_HEIGHT_RES.getCode());
        setData(data);
    }
}