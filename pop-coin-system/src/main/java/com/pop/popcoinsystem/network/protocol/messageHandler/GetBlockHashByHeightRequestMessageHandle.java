package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.GetBlockHashByHeightRequestMessage;
import com.pop.popcoinsystem.network.protocol.message.GetBlockHashByHeightResponseMessage;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.service.BlockChainServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;

@Slf4j
public class GetBlockHashByHeightRequestMessageHandle implements MessageHandler {

    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException {
        return doHandle(kademliaNodeServer, (GetBlockHashByHeightRequestMessage) message);
    }

    protected GetBlockHashByHeightResponseMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull GetBlockHashByHeightRequestMessage message) throws InterruptedException {
        log.info("收到请求 -> 根据高度查询区块");
        NodeInfo sender = message.getSender();
        NodeInfo me = kademliaNodeServer.getNodeInfo();
        Long data = message.getData();
        BlockChainServiceImpl blockChainService = kademliaNodeServer.getBlockChainService();
        Block mainBlockByHeight = blockChainService.getMainBlockByHeight(data);

        GetBlockHashByHeightResponseMessage responseMessage = new GetBlockHashByHeightResponseMessage();
        responseMessage.setSender(me);
        responseMessage.setReceiver(sender);
        responseMessage.setData(mainBlockByHeight);
        return responseMessage;
    }

}