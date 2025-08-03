package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.*;
import com.pop.popcoinsystem.service.blockChain.BlockChainServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;

@Slf4j
public class FindForkPointRequestMessageHandle implements MessageHandler{
    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws Exception {
        return doHandle(kademliaNodeServer, (FindForkPointRequestMessage) message);
    }

    protected FindForkPointRequestMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull FindForkPointRequestMessage message) throws Exception {
        log.info("收到分叉点查询请求，开始查找共同区块");
        NodeInfo sender = message.getSender();
        NodeInfo me = kademliaNodeServer.getNodeInfo();
        BlockChainServiceImpl chainService = kademliaNodeServer.getBlockChainService();
        byte[] startHash = message.getData(); // 通常是创世区块哈希
        return null;
    }


}
