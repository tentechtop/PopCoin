package com.pop.popcoinsystem.network.protocol.messageHandler;

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
      return doHandle(kademliaNodeServer, (PongKademliaMessage) message);
    }

    protected EmptyKademliaMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull PongKademliaMessage message) throws InterruptedException, FullBucketException, ConnectException {
        log.info("收到pong");
        //更新节点
        NodeInfo sender = message.getSender();
        RoutingTable routingTable = kademliaNodeServer.getRoutingTable();
        routingTable.update(sender);
        return null;
    }

}
