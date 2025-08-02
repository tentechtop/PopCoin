package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.exception.UnsupportedChainException;
import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.*;
import com.pop.popcoinsystem.network.protocol.messageData.BlockHeadersRes;
import com.pop.popcoinsystem.network.protocol.messageData.HeadersRequestParam;
import com.pop.popcoinsystem.service.impl.BlockChainServiceImpl;
import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.net.ConnectException;
import java.util.List;

@Slf4j
public class GetHeadersRequestMessageHandle implements MessageHandler{
    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException, FullBucketException, ConnectException, UnsupportedChainException {
        return doHandle(kademliaNodeServer, (GetHeadersRequestMessage) message);
    }


    protected GetHeadersRequestMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull GetHeadersRequestMessage message) throws InterruptedException, ConnectException {
        NodeInfo me = kademliaNodeServer.getNodeInfo();
        NodeInfo sender = message.getSender();
        HeadersRequestParam data = message.getData();
        byte[] start = data.getStart();
        byte[] end = data.getEnd();
        log.info("收到区块头范围查询请求（start: {}, end: {}）",
                CryptoUtil.bytesToHex(start),
                CryptoUtil.bytesToHex(end));

        BlockChainServiceImpl blockChainService = kademliaNodeServer.getBlockChainService();
        List<Block> blocksInRange =  blockChainService.getBlockByStartHashAndEndHashByHeight(data.getStart(), data.getEnd());
        if (blocksInRange.isEmpty()) {
            log.warn("未查询到指定范围内的区块");
        } else {
            log.info("查询到 {} 个区块，准备返回", blocksInRange.size());
        }


        // 2. 封装响应（包含区块头列表、起始/结束哈希）
        BlockHeadersRes blockHeadersRes = new BlockHeadersRes(start, end, blocksInRange);
        //返回响应
        GetHeadersResponseMessage responseMessage = new GetHeadersResponseMessage();
        responseMessage.setSender(me);
        responseMessage.setReceiver(sender);
        responseMessage.setData(blockHeadersRes);
        kademliaNodeServer.getTcpClient().sendAsyncMessage(responseMessage);
        return null;
    }
}
