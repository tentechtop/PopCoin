package com.pop.popcoinsystem.service;

import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.RpcProxyFactory;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.FindNodeResult;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.common.RoutingTable;
import com.pop.popcoinsystem.network.protocol.message.FindNodeResponseMessage;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.RpcRequestMessage;
import com.pop.popcoinsystem.network.protocol.messageData.RpcRequestData;
import com.pop.popcoinsystem.service.transaction.TransactionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.net.ConnectException;
import java.util.List;

@Service
public class SyncBlockChainService {




    @Lazy
    @Autowired
    private KademliaNodeServer kademliaNodeServer;

    public Result sendTextMessage(String message) throws Exception {
        RpcRequestData rpcRequestData = new RpcRequestData();

        // 1. 准备目标服务节点信息
        NodeInfo nodeInfo = new NodeInfo();
        nodeInfo.setId(BigInteger.ONE);
        nodeInfo.setIpv4("192.168.137.102");
        nodeInfo.setTcpPort(8334);
        nodeInfo.setUdpPort(8333);

        // 2. 创建代理工厂
        RpcProxyFactory proxyFactory = new RpcProxyFactory(kademliaNodeServer, nodeInfo);
        // 3. 获取服务代理对象
        TransactionService transactionService = proxyFactory.createProxy(TransactionService.class);
        // 4. 像调用本地方法一样调用远程服务
        String result = transactionService.sayHello("Hello World"); // 底层自动完成远程调用

        return Result.ok(result);
    }


    public Result findNode() throws Exception {
        RoutingTable routingTable = kademliaNodeServer.getRoutingTable();
        NodeInfo nodeInfo = kademliaNodeServer.getNodeInfo();


        NodeInfo nodeInfo1 = new NodeInfo();
        nodeInfo1.setId(BigInteger.ONE);
        nodeInfo1.setIpv4("192.168.137.102");
        nodeInfo1.setTcpPort(8334);
        nodeInfo1.setUdpPort(8333);


        List<ExternalNodeInfo> closest = routingTable.findClosest(nodeInfo.getId());
        FindNodeResult findNodeResult = new FindNodeResult();
        findNodeResult.setNodes(closest);
        findNodeResult.setDestinationId(nodeInfo.getId());
        FindNodeResponseMessage findNodeResponseMessage = new FindNodeResponseMessage();
        findNodeResponseMessage.setSender(nodeInfo);
        findNodeResponseMessage.setReceiver(nodeInfo1);
        findNodeResponseMessage.setData(findNodeResult);
        KademliaMessage kademliaMessage = kademliaNodeServer.getTcpClient().sendMessageWithResponse(findNodeResponseMessage);
        return Result.ok(kademliaMessage);
    }
}
