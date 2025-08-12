package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PingKademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PongKademliaMessage;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;

@Slf4j
public class PingMessageHandler implements MessageHandler {

    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException, FullBucketException {

        return doHandle(kademliaNodeServer, message);
    }
    protected PongKademliaMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull KademliaMessage message) throws InterruptedException, FullBucketException {
        log.info("收到ping");
        NodeInfo sender = message.getSender();
        kademliaNodeServer.getRoutingTable().update(sender);
        PongKademliaMessage pongKademliaMessage = new PongKademliaMessage();
        long requestId = message.getRequestId();//响应消息必须保持一致
        pongKademliaMessage.setSender(kademliaNodeServer.getNodeInfo());
        pongKademliaMessage.setReceiver(message.getSender());
        pongKademliaMessage.setRequestId(requestId);
        pongKademliaMessage.setResponse(true);
        return pongKademliaMessage;
    }

}
