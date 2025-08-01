package com.pop.popcoinsystem.network.protocol.message;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.network.protocol.MessageType;
import lombok.Builder;
import lombok.ToString;

@Builder
@ToString(callSuper = true)
public class GetBlockHashByHeightResponse extends KademliaMessage<Block>{

    // 添加无参构造函数
    public GetBlockHashByHeightResponse() {
        super(MessageType.GET_BLOCK_BY_HEIGHT_RES.getCode());
    }

    public GetBlockHashByHeightResponse(Block data) {
        super(MessageType.GET_BLOCK_BY_HEIGHT_RES.getCode());
        setData(data);
    }
}