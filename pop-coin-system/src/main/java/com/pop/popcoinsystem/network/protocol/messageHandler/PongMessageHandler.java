package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.common.RoutingTable;
import com.pop.popcoinsystem.network.protocol.message.*;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.net.ConnectException;

@Slf4j
public class PongMessageHandler implements MessageHandler{

    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException, FullBucketException, ConnectException {
      return doHandle(kademliaNodeServer,  message);
    }

    protected EmptyKademliaMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull KademliaMessage message) throws InterruptedException, FullBucketException, ConnectException {
        log.info("收到pong");
        NodeInfo sender = message.getSender();
        kademliaNodeServer.getRoutingTable().update(sender);
        return null;
    }

}
