package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.exception.UnsupportedChainException;
import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.*;
import com.pop.popcoinsystem.network.protocol.messageData.BlockHeadersRes;
import com.pop.popcoinsystem.network.protocol.messageData.HeadersRequestParam;
import com.pop.popcoinsystem.service.blockChain.BlockChainServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.net.ConnectException;
import java.util.Arrays;
import java.util.List;

@Slf4j
public class GetHeadersResponseMessageHandle implements MessageHandler{
    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException, FullBucketException, ConnectException, UnsupportedChainException {
        return doHandle(kademliaNodeServer, (GetHeadersResponseMessage) message);
    }

    protected GetHeadersResponseMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull GetHeadersResponseMessage message) throws InterruptedException, ConnectException {
        log.info("收到获取区块头响应");
        NodeInfo sender = message.getSender();
        NodeInfo me = kademliaNodeServer.getNodeInfo();
        BlockHeadersRes data = message.getData();
        List<Block> remoteBlocks = data.getHeaders();//暂时用完整区块 后续再升级
        // 1. 验证区块连续性和有效性（核心步骤）
        Block lastLocalBlock = null;
        BlockChainServiceImpl blockChainService = kademliaNodeServer.getBlockChainService();

        for (Block remoteBlock : remoteBlocks) {
            // 验证1：区块高度是否连续（防止跳跃）
            if (lastLocalBlock != null && remoteBlock.getHeight() != lastLocalBlock.getHeight() + 1) {
                log.error("区块高度不连续，同步中断");
                return null;
            }
            // 验证2：前序哈希是否匹配上一区块（防止篡改）
            if (lastLocalBlock != null && !Arrays.equals(remoteBlock.getPreviousHash(), lastLocalBlock.getHash())) {
                log.error("前序哈希不匹配，区块无效");
                return null;
            }
            // 验证3：PoW工作量是否符合难度（仅PoW场景）
            if (!blockChainService.validateBlockPoW(remoteBlock)) {
                log.error("区块PoW无效，同步中断");
                return null;
            }
            lastLocalBlock = remoteBlock;
        }

        // 2. 将有效区块添加到本地链（区分主链和备选链）
        for (Block validBlock : remoteBlocks) {
            // 若区块高度高于本地主链，直接添加到主链
            if (validBlock.getHeight() > blockChainService.getMainLatestHeight()) {
                blockChainService.addBlockToMainChain(validBlock);
                log.info("添加新区块到主链（高度: {}）", validBlock.getHeight());
            }
            // 若高度相同但哈希不同，作为备选链保留（可能后续成为主链）
            else if (validBlock.getHeight() == blockChainService.getMainLatestHeight()
                    && !Arrays.equals(validBlock.getHash(), blockChainService.getMainLatestBlockHash())) {
                blockChainService.addBlockToAltChain(validBlock);
                log.info("添加分叉区块到备选链（高度: {}）", validBlock.getHeight());
            }
        }

        // 3. 若未同步到远程最新区块，继续请求剩余部分
        Block lastRemoteBlock = remoteBlocks.get(remoteBlocks.size() - 1);
        if (!Arrays.equals(lastRemoteBlock.getHash(), message.getData().getEnd())) {
            log.info("继续同步剩余区块（当前最新高度: {}）", lastRemoteBlock.getHeight());
            HeadersRequestParam nextParam = new HeadersRequestParam(lastRemoteBlock.getHash(), message.getData().getEnd());
            GetHeadersRequestMessage nextRequest = new GetHeadersRequestMessage();
            nextRequest.setSender(me);
            nextRequest.setReceiver(sender);
            nextRequest.setData(nextParam);
            kademliaNodeServer.getTcpClient().sendMessage(nextRequest);
        }
        return null;
    }
}
