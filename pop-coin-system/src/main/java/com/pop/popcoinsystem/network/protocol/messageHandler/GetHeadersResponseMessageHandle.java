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

        return null;
    }
}
