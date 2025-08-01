package com.pop.popcoinsystem.network.protocol.message;

import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.network.protocol.message.content.HeadersRequestParam;
import lombok.Builder;
import lombok.ToString;

@Builder
@ToString(callSuper = true)
public class GetBlockHashByHeightRequest extends KademliaMessage<Long>{

    // 添加无参构造函数
    public GetBlockHashByHeightRequest() {
        super(MessageType.GET_BLOCK_BY_HEIGHT_REQ.getCode());
    }

    public GetBlockHashByHeightRequest(Long data) {
        super(MessageType.GET_BLOCK_BY_HEIGHT_REQ.getCode());
        setData(data);
    }
}