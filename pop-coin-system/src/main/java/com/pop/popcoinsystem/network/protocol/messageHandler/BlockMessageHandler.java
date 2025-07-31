package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.protocol.message.BlockMessage;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.TransactionMessage;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;

@Slf4j
public class BlockMessageHandler implements MessageHandler {

    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException {
        return doHandle(kademliaNodeServer, (BlockMessage) message);
    }


    protected BlockMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull BlockMessage message) throws InterruptedException {
        Block data = message.getData();
        log.info("收到区块消息消息{}",data);
        kademliaNodeServer.getBlockChainService().verifyBlock(data);
        return null;
    }

}
