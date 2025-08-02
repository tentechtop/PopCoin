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

        // 1. 二分查找分叉点（核心逻辑）
        Block forkPoint = findForkPoint(kademliaNodeServer,message, chainService, startHash, chainService.getMainLatestBlockHash(), sender, kademliaNodeServer);


        return null;
    }

    private Block findForkPoint(KademliaNodeServer kademliaNodeServer, FindForkPointRequestMessage message, BlockChainServiceImpl chainService, byte[] startHash, byte[] endHash, NodeInfo remoteNode, KademliaNodeServer server) throws Exception {
        NodeInfo me = kademliaNodeServer.getNodeInfo();
        NodeInfo sender = message.getSender();

        Block startBlock = chainService.getBlockByHash(startHash);
        Block endBlock = chainService.getBlockByHash(endHash);
        if (startBlock == null || endBlock == null) return null;

        long low = startBlock.getHeight();
        long high = endBlock.getHeight();
        Block forkPoint = startBlock; // 初始化为创世区块

        while (low <= high) {
            long mid = (low + high) / 2;
            byte[] midHash = chainService.getMainBlockHashByHeight(mid);
            if (midHash == null) {
                high = mid - 1;
                continue;
            }
            // 向远程节点查询该高度的区块哈希，判断是否相同
            log.info("向节点{}查询高度{}的区块哈希", sender, mid);
            GetBlockHashByHeightRequestMessage request = new GetBlockHashByHeightRequestMessage(mid);
            request.setSender(me);
            request.setReceiver(sender);
            request.setResponse(true);
            GetBlockHashByHeightResponseMessageHandle response = null;
            KademliaMessage kademliaMessage = server.getTcpClient().sendMessageWithResponse(request);



/*            if (response != null && Arrays.equals(response.getBlockHash(), midHash)) {
                // 中间区块相同，向更高处查找
                forkPoint = chainService.getBlockByHash(midHash);
                low = mid + 1;
            } else {
                // 中间区块不同，向低处查找
                high = mid - 1;
            }*/


        }
        return forkPoint;
    }
}
