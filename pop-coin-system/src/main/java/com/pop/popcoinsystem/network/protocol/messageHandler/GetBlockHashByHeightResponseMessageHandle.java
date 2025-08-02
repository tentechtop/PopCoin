package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.GetBlockHashByHeightResponseMessage;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;

@Slf4j
public class GetBlockHashByHeightResponseMessageHandle implements MessageHandler {

    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException {
        return doHandle(kademliaNodeServer, (GetBlockHashByHeightResponseMessage) message);
    }


    protected GetBlockHashByHeightResponseMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull GetBlockHashByHeightResponseMessage message) throws InterruptedException {
        log.info("收到获取块哈希响应");
        NodeInfo sender = message.getSender();





        return null;
    }

}