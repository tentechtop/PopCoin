package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.FindNodeResult;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.common.RoutingTable;
import com.pop.popcoinsystem.network.protocol.message.*;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.math.BigInteger;
import java.net.ConnectException;
import java.util.List;

@Slf4j
public class FindNodeRequestMessageHandler implements MessageHandler{

    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException, ConnectException {
        return doHandle(kademliaNodeServer, (FindNodeRequestMessage) message);
    }



    protected FindNodeRequestMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull FindNodeRequestMessage message) throws InterruptedException, ConnectException {
        log.info("收到查找节点请求");
        NodeInfo sender = message.getSender();
        BigInteger findId = message.getData();
        RoutingTable routingTable = kademliaNodeServer.getRoutingTable();
        List<ExternalNodeInfo> closest = routingTable.findClosest(findId);
        FindNodeResult findNodeResult = new FindNodeResult();
        findNodeResult.setNodes(closest);
        findNodeResult.setDestinationId(findId);
        FindNodeResponseMessage findNodeResponseMessage = new FindNodeResponseMessage();
        findNodeResponseMessage.setSender(kademliaNodeServer.getNodeInfo());
        findNodeResponseMessage.setReceiver(sender);
        findNodeResponseMessage.setData(findNodeResult);
        kademliaNodeServer.getTcpClient().sendMessage(findNodeResponseMessage);
        return null;
    }

}
