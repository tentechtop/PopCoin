package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.network.protocol.message.BlockMessage;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.util.ByteUtils;
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
        log.debug("收到区块消息{}",data);
        byte[] bytes = data.getHash();
        long blockMessageId = ByteUtils.bytesToLong(bytes);
        if (kademliaNodeServer.getBroadcastMessages().getIfPresent(blockMessageId) != null) {
            log.debug("接收已处理的区块消息 {}，丢弃", blockMessageId);
        }else {
            // 记录：标记为已处理
            kademliaNodeServer.getBroadcastMessages().put(blockMessageId, Boolean.TRUE);
            kademliaNodeServer.getBlockChainService().verifyBlock(data,false);
            kademliaNodeServer.broadcastMessage(message);
        }
        return null;
    }

}
