package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.FindNodeResult;
import com.pop.popcoinsystem.network.protocol.message.FindNodeResponseMessage;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.net.ConnectException;
import java.util.List;

@Slf4j
public class FindNodeResponseMessageHandler implements MessageHandler{

    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws FullBucketException, InterruptedException, ConnectException {
        return doHandle(kademliaNodeServer, (FindNodeResponseMessage) message);
    }

    protected FindNodeResponseMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull FindNodeResponseMessage message) throws InterruptedException, ConnectException, FullBucketException {
        FindNodeResult data = message.getData();
        List<ExternalNodeInfo> nodes = data.getNodes();
        NodeInfo nodeInfo = kademliaNodeServer.getNodeInfo();
        //去除自己
        nodes.removeIf(node -> node.getId().equals(nodeInfo.getId()));
        //去除和自己IP 端口一样的节点
        nodes.removeIf(node -> ( node.getIpv4().equals(nodeInfo.getIpv4()) && (node.getTcpPort() == nodeInfo.getTcpPort() || node.getTcpPort() == nodeInfo.getUdpPort())));
        log.debug("收到节点查询结果{}",nodes);
        for(ExternalNodeInfo node:nodes){
            kademliaNodeServer.getRoutingTable().update(node);
        }
        return null;
    }
}
