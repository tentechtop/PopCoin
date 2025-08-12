package com.pop.popcoinsystem.service;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.block.BlockDTO;
import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PingKademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PongKademliaMessage;
import com.pop.popcoinsystem.network.rpc.RpcService;
import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.network.rpc.RpcProxyFactory;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.messageData.RpcRequestData;
import com.pop.popcoinsystem.service.blockChain.BlockChainService;
import com.pop.popcoinsystem.service.transaction.TransactionService;
import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.net.ConnectException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class SyncBlockChainServiceImpl {

    @Lazy
    @Autowired
    private KademliaNodeServer kademliaNodeServer;


    public Result sendTextMessage(String message) throws Exception {
        RpcRequestData rpcRequestData = new RpcRequestData();

        // 1. 准备目标服务节点信息
        NodeInfo nodeInfo = new NodeInfo();
        nodeInfo.setId(BigInteger.ONE);
        nodeInfo.setIpv4("114.67.219.160");
        nodeInfo.setTcpPort(8333);


        // 2. 创建代理工厂
        RpcProxyFactory proxyFactory = new RpcProxyFactory(kademliaNodeServer,nodeInfo);
        // 3. 获取服务代理对象
        TransactionService transactionService = proxyFactory.createProxy(TransactionService.class);
        // 4. 像调用本地方法一样调用远程服务
        String result = transactionService.sayHello("Hello World"); // 底层自动完成远程调用
        return Result.ok(result);
    }


    public Result findNode() throws Exception {
        RpcProxyFactory proxyFactory = new RpcProxyFactory(kademliaNodeServer);
        BlockChainService blockChainService = proxyFactory.createProxy(BlockChainService.class);
        Result blockByRange = blockChainService.getBlockByRange(0, 49);
        return blockByRange;
    }

    public Result getBlockByHash(String hash) throws InterruptedException, ConnectException {
   /*     // 1. 准备目标服务节点信息
        NodeInfo nodeInfo = new NodeInfo();
        nodeInfo.setId(BigInteger.ONE);
        nodeInfo.setIpv4("192.168.137.102");
        nodeInfo.setTcpPort(8334);
        nodeInfo.setUdpPort(8333);

        RpcProxyFactory proxyFactory = new RpcProxyFactory(kademliaNodeServer,nodeInfo);
        BlockChainService blockChainService = proxyFactory.createProxy(BlockChainService.class);
        Block blockByHash = blockChainService.getBlockByHash(CryptoUtil.hexToBytes(hash));
        PingKademliaMessage pingKademliaMessage = new PingKademliaMessage();
        pingKademliaMessage.setSender(kademliaNodeServer.getNodeInfo());//本节点信息
        pingKademliaMessage.setReceiver(nodeInfo);
        pingKademliaMessage.setReqResId();
        pingKademliaMessage.setResponse(false);
        kademliaNodeServer.getTcpClient().sendMessage(pingKademliaMessage);*/
        return Result.ok();
    }

    public Result sendPing() throws Exception {
        NodeInfo nodeInfo = new NodeInfo();
        nodeInfo.setId(BigInteger.ONE);
        nodeInfo.setIpv4("114.67.219.160");
        nodeInfo.setTcpPort(8999);


        RpcProxyFactory proxyFactory = new RpcProxyFactory(kademliaNodeServer,nodeInfo);
        RpcService proxy = proxyFactory.createProxy(RpcService.class);
        PongKademliaMessage ping = proxy.ping();
        return Result.ok(ping);
    }
}
