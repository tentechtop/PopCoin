package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.*;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;

@Slf4j
public class PongMessageHandler implements MessageHandler{

    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException, FullBucketException {
      return doHandle(kademliaNodeServer, (PongKademliaMessage) message);
    }

    protected EmptyKademliaMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull PongKademliaMessage message) throws InterruptedException, FullBucketException {
        log.info("收到pong");//pong信息中应该携带网络信息 消息头应该有网络消息
        //主动和节点握手
        NodeInfo sender = message.getSender();
        ExternalNodeInfo externalNodeInfo = BeanCopyUtils.copyObject(sender, ExternalNodeInfo.class);
        kademliaNodeServer.getRoutingTable().update(externalNodeInfo);
        HandshakeRequestMessage handshakeRequestMessage = new HandshakeRequestMessage(kademliaNodeServer.getExternalNodeInfo());
        handshakeRequestMessage.setSender(kademliaNodeServer.getNodeInfo());
        handshakeRequestMessage.setReceiver(message.getSender());
        kademliaNodeServer.getUdpClient().sendMessage(handshakeRequestMessage);
        return new EmptyKademliaMessage();
    }

}
