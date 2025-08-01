package com.pop.popcoinsystem.network.protocol.message;

import com.pop.popcoinsystem.network.protocol.MessageType;
import lombok.Builder;
import lombok.ToString;

@Builder
@ToString(callSuper = true)
public class GetBlockHashByHeightRequestMessage extends KademliaMessage<Long>{

    // 添加无参构造函数
    public GetBlockHashByHeightRequestMessage() {
        super(MessageType.GET_BLOCK_BY_HEIGHT_REQ.getCode());
    }

    public GetBlockHashByHeightRequestMessage(Long data) {
        super(MessageType.GET_BLOCK_BY_HEIGHT_REQ.getCode());
        setData(data);
    }
}