package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PingKademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PongKademliaMessage;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.util.UUID;

@Slf4j
public class PingMessageHandler implements MessageHandler {

    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException {
        return doHandle(kademliaNodeServer, (PingKademliaMessage) message);
    }
    protected PongKademliaMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull PingKademliaMessage message) throws InterruptedException {
        log.info("收到ping");
        PongKademliaMessage pongKademliaMessage = new PongKademliaMessage();
        pongKademliaMessage.setSender(kademliaNodeServer.getNodeInfo());
        pongKademliaMessage.setReceiver(message.getSender());
        kademliaNodeServer.getUdpClient().sendMessage(pongKademliaMessage);
        return null;
    }

}
