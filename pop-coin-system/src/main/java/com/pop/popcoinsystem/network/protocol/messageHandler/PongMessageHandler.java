package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.network.protocol.message.*;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;

@Slf4j
public class PongMessageHandler implements MessageHandler{

    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException, FullBucketException {
      return doHandle(kademliaNodeServer, (PongKademliaMessage) message);
    }

    protected EmptyKademliaMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull PongKademliaMessage message) throws InterruptedException {
        //请求握手
        HandshakeRequestMessage handshakeRequestMessage = new HandshakeRequestMessage(kademliaNodeServer.getExternalNodeInfo());
        handshakeRequestMessage.setSender(kademliaNodeServer.getNodeInfo());
        handshakeRequestMessage.setReceiver(message.getSender());
        kademliaNodeServer.getUdpClient().sendMessage(handshakeRequestMessage);
        return new EmptyKademliaMessage();
    }

}
