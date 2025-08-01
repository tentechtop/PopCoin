package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.TransactionMessage;
import com.pop.popcoinsystem.network.protocol.messageHandler.MessageHandler;
import com.pop.popcoinsystem.service.BlockChainService;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.Serializable;

@Slf4j
@Service
public class TransactionMessageHandler implements MessageHandler {




    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException {
        return doHandle(kademliaNodeServer, (TransactionMessage) message);
    }


    protected TransactionMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull TransactionMessage message) throws InterruptedException {
        Transaction data = message.getData();
        log.info("收到交易消息{}",data);

        //事件生产者
        //接收交易消息后，通过RingBuffer发布事件到 Disruptor，触发后续处理。
        // 通过管理器发布事件，无需直接依赖BlockChainService
        kademliaNodeServer.getBlockChainService().verifyAndAddTradingPool(data);
        return null;
    }

}
