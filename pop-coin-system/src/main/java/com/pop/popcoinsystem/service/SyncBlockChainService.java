package com.pop.popcoinsystem.service;

import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.RpcRequestMessage;
import com.pop.popcoinsystem.network.protocol.messageData.RpcRequestData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigInteger;

@Service
public class SyncBlockChainService {


    @Lazy
    @Autowired
    private KademliaNodeServer kademliaNodeServer;

    public Result sendTextMessage(String message) throws Exception {
        RpcRequestData rpcRequestData = new RpcRequestData();
        rpcRequestData.setServiceName("TransactionService");
        rpcRequestData.setMethodName("sayHello");
        rpcRequestData.setParameters(new Object[]{message});
        rpcRequestData.setParamTypes(new Class[]{String.class});

        RpcRequestMessage rpcRequestMessage = new RpcRequestMessage();
        rpcRequestMessage.setData(rpcRequestData);
        rpcRequestMessage.setSender(kademliaNodeServer.getNodeInfo());

        NodeInfo nodeInfo = new NodeInfo();
        nodeInfo.setId(BigInteger.ONE);
        nodeInfo.setIpv4("192.168.137.102");
        nodeInfo.setTcpPort(8334);
        nodeInfo.setUdpPort(8333);
        rpcRequestMessage.setReceiver(nodeInfo);

        KademliaMessage kademliaMessage = kademliaNodeServer.getTcpClient().sendMessageWithResponse(rpcRequestMessage);
        return Result.ok(kademliaMessage.getData());
    }
}
