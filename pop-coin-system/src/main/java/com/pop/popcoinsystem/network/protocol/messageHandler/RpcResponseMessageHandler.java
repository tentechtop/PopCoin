package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.network.protocol.message.EmptyKademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.RpcResponseMessage;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;

@Slf4j
public class RpcResponseMessageHandler implements MessageHandler {

    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException {
        return doHandle(kademliaNodeServer, (RpcResponseMessage) message);
    }


    protected EmptyKademliaMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull RpcResponseMessage rpcRequest) throws InterruptedException {
        long requestId = rpcRequest.getRequestId();//响应消息必须保持一致

        return null;
    }

}
