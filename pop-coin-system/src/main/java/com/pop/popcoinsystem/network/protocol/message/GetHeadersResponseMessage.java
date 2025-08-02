package com.pop.popcoinsystem.network.protocol.message;

import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.network.protocol.messageData.BlockHeadersRes;
import lombok.ToString;

@ToString(callSuper = true)
public class GetHeadersResponseMessage extends KademliaMessage<BlockHeadersRes>{
    // 添加无参构造函数
    public GetHeadersResponseMessage() {
        super(MessageType.GET_BLOCK_HEADERS_RES.getCode());
    }

    public GetHeadersResponseMessage(BlockHeadersRes data) {
        super(MessageType.GET_BLOCK_HEADERS_RES.getCode());
        setData(data);
    }
}
