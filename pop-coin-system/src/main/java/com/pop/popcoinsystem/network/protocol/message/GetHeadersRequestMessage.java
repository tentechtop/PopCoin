package com.pop.popcoinsystem.network.protocol.message;

import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.network.protocol.message.content.Handshake;
import com.pop.popcoinsystem.network.protocol.message.content.HeadersRequestParam;
import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.ToString;

@ToString(callSuper = true)
public class GetHeadersRequestMessage extends KademliaMessage<HeadersRequestParam>{

    // 添加无参构造函数
    public GetHeadersRequestMessage() {
        super(MessageType.GET_BLOCK_HEADERS_REQ.getCode());
    }

    public GetHeadersRequestMessage(HeadersRequestParam data) {
        super(MessageType.GET_BLOCK_HEADERS_REQ.getCode());
        setData(data);
    }
}
